/*
 * Copyright (C) 2010, Christian Halstrick <christian.halstrick@sap.com>,
 * Copyright (C) 2010-2012, Matthias Sohn <matthias.sohn@sap.com>
 * Copyright (C) 2012, Research In Motion Limited
 * Copyright (C) 2017, Obeo (mathieu.cartaud@obeo.fr)
 * Copyright (C) 2018, 2022 Thomas Wolf <thomas.wolf@paranor.ch> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */
package org.eclipse.jgit.merge;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.Instant.EPOCH;
import static org.eclipse.jgit.diff.DiffAlgorithm.SupportedAlgorithm.HISTOGRAM;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_DIFF_SECTION;
import static org.eclipse.jgit.lib.ConfigConstants.CONFIG_KEY_ALGORITHM;
import static org.eclipse.jgit.lib.Constants.OBJ_BLOB;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.jgit.annotations.NonNull;
import org.eclipse.jgit.attributes.Attributes;
import org.eclipse.jgit.diff.DiffAlgorithm;
import org.eclipse.jgit.diff.DiffAlgorithm.SupportedAlgorithm;
import org.eclipse.jgit.diff.RawText;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuildIterator;
import org.eclipse.jgit.dircache.DirCacheCheckout.CheckoutMetadata;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.errors.BinaryBlobException;
import org.eclipse.jgit.lib.Config;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.CoreConfig.EolStreamType;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.storage.pack.PackConfig;
import org.eclipse.jgit.submodule.SubmoduleConflict;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.NameConflictTreeWalk;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.WorkingTreeIterator;
import org.eclipse.jgit.treewalk.filter.TreeFilter;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.LfsFactory;
import org.eclipse.jgit.util.WorkTreeUpdater;
import org.eclipse.jgit.util.WorkTreeUpdater.StreamLoader;
import org.eclipse.jgit.util.TemporaryBuffer;

/**
 * A three-way merger performing a content-merge if necessary
 */
public class ResolveMerger extends ThreeWayMerger {

	/**
	 * If the merge fails (means: not stopped because of unresolved conflicts)
	 * this enum is used to explain why it failed
	 */
	public enum MergeFailureReason {
		/** the merge failed because of a dirty index */
		DIRTY_INDEX,
		/** the merge failed because of a dirty workingtree */
		DIRTY_WORKTREE,
		/** the merge failed because of a file could not be deleted */
		COULD_NOT_DELETE
	}

	/**
	 * The tree walk which we'll iterate over to merge entries.
	 *
	 * @since 3.4
	 */
	protected NameConflictTreeWalk tw;

	/**
	 * string versions of a list of commit SHA1s
	 *
	 * @since 3.0
	 */
	protected String[] commitNames;

	/**
	 * Index of the base tree within the {@link #tw tree walk}.
	 *
	 * @since 3.4
	 */
	protected static final int T_BASE = 0;

	/**
	 * Index of our tree in withthe {@link #tw tree walk}.
	 *
	 * @since 3.4
	 */
	protected static final int T_OURS = 1;

	/**
	 * Index of their tree within the {@link #tw tree walk}.
	 *
	 * @since 3.4
	 */
	protected static final int T_THEIRS = 2;

	/**
	 * Index of the index tree within the {@link #tw tree walk}.
	 *
	 * @since 3.4
	 */
	protected static final int T_INDEX = 3;

	/**
	 * Index of the working directory tree within the {@link #tw tree walk}.
	 *
	 * @since 3.4
	 */
	protected static final int T_FILE = 4;

	/**
	 * Handler for repository I/O actions.
	 */
	protected WorkTreeUpdater workTreeUpdater;

	/**
	 * merge result as tree
	 *
	 * @since 3.0
	 */
	protected ObjectId resultTree;

	/**
	 * Files modified during this operation. Note this list is only updated after a successful write.
	 */
	protected List<String> modifiedFiles = new ArrayList<>();

	/**
	 * Paths that could not be merged by this merger because of an unsolvable
	 * conflict.
	 *
	 * @since 3.4
	 */
	protected List<String> unmergedPaths = new ArrayList<>();

	/**
	 * Low-level textual merge results. Will be passed on to the callers in case
	 * of conflicts.
	 *
	 * @since 3.4
	 */
	protected Map<String, MergeResult<? extends Sequence>> mergeResults = new HashMap<>();

	/**
	 * Paths for which the merge failed altogether.
	 *
	 * @since 3.4
	 */
	protected Map<String, MergeFailureReason> failingPaths = new HashMap<>();

	/**
	 * Updated as we merge entries of the tree walk. Tells us whether we should
	 * recurse into the entry if it is a subtree.
	 *
	 * @since 3.4
	 */
	protected boolean enterSubtree;

	/**
	 * Set to true if this merge should work in-memory. The repos dircache and
	 * workingtree are not touched by this method. Eventually needed files are
	 * created as temporary files and a new empty, in-memory dircache will be
	 * used instead the repo's one. Often used for bare repos where the repo
	 * doesn't even have a workingtree and dircache.
	 * @since 3.0
	 */
	protected boolean inCore;

	/**
	 * Directory cache
	 * @since 3.0
	 */
	protected DirCache dircache;

	/**
	 * The iterator to access the working tree. If set to <code>null</code> this
	 * merger will not touch the working tree.
	 * @since 3.0
	 */
	protected WorkingTreeIterator workingTreeIterator;

	/**
	 * our merge algorithm
	 * @since 3.0
	 */
	protected MergeAlgorithm mergeAlgorithm;

	/**
	 * The {@link ContentMergeStrategy} to use for "resolve" and "recursive"
	 * merges.
	 */
	@NonNull
	private ContentMergeStrategy contentStrategy = ContentMergeStrategy.CONFLICT;

	private static MergeAlgorithm getMergeAlgorithm(Config config) {
		SupportedAlgorithm diffAlg = config.getEnum(
				CONFIG_DIFF_SECTION, null, CONFIG_KEY_ALGORITHM,
				HISTOGRAM);
		return new MergeAlgorithm(DiffAlgorithm.getAlgorithm(diffAlg));
	}

	private static String[] defaultCommitNames() {
		return new String[]{"BASE", "OURS", "THEIRS"}; //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	}

	private static final Attributes NO_ATTRIBUTES = new Attributes();

	/**
	 * Constructor for ResolveMerger.
	 *
	 * @param local
	 *            the {@link org.eclipse.jgit.lib.Repository}.
	 * @param inCore
	 *            a boolean.
	 */
	protected ResolveMerger(Repository local, boolean inCore) {
		super(local);
		Config config = local.getConfig();
		mergeAlgorithm = getMergeAlgorithm(config);
		commitNames = defaultCommitNames();
		this.inCore = inCore;
	}

	/**
	 * Constructor for ResolveMerger.
	 *
	 * @param local
	 *            the {@link org.eclipse.jgit.lib.Repository}.
	 */
	protected ResolveMerger(Repository local) {
		this(local, false);
	}

	/**
	 * Constructor for ResolveMerger.
	 *
	 * @param inserter
	 *            an {@link org.eclipse.jgit.lib.ObjectInserter} object.
	 * @param config
	 *            the repository configuration
	 * @since 4.8
	 */
	protected ResolveMerger(ObjectInserter inserter, Config config) {
		super(inserter);
		mergeAlgorithm = getMergeAlgorithm(config);
		commitNames = defaultCommitNames();
		inCore = true;
	}

	/**
	 * Retrieves the content merge strategy for content conflicts.
	 *
	 * @return the {@link ContentMergeStrategy} in effect
	 * @since 5.12
	 */
	@NonNull
	public ContentMergeStrategy getContentMergeStrategy() {
		return contentStrategy;
	}

	/**
	 * Sets the content merge strategy for content conflicts.
	 *
	 * @param strategy
	 *            {@link ContentMergeStrategy} to use
	 * @since 5.12
	 */
	public void setContentMergeStrategy(ContentMergeStrategy strategy) {
		contentStrategy = strategy == null ? ContentMergeStrategy.CONFLICT
				: strategy;
	}

	/** {@inheritDoc} */
	@Override
	protected boolean mergeImpl() throws IOException {
		return mergeTrees(mergeBase(), sourceTrees[0], sourceTrees[1],
				false);
	}

	/**
	 * adds a new path with the specified stage to the index builder
	 *
	 * @param path
	 * @param p
	 * @param stage
	 * @param lastMod
	 * @param len
	 * @return the entry which was added to the index
	 */
	private DirCacheEntry add(byte[] path, CanonicalTreeParser p, int stage,
			Instant lastMod, long len) {
		if (p != null && !p.getEntryFileMode().equals(FileMode.TREE)) {
			return workTreeUpdater.addExistingToIndex(p.getEntryObjectId(), path,
					p.getEntryFileMode(), stage,
					lastMod, (int) len);
		}
		return null;
	}

	/**
	 * adds a entry to the index builder which is a copy of the specified
	 * DirCacheEntry
	 *
	 * @param e
	 *            the entry which should be copied
	 *
	 * @return the entry which was added to the index
	 */
	private DirCacheEntry keep(DirCacheEntry e) {
		return workTreeUpdater.addExistingToIndex(e.getObjectId(), e.getRawPath(), e.getFileMode(),
				e.getStage(), e.getLastModifiedInstant(), e.getLength());
	}

	/**
	 * Adds a {@link DirCacheEntry} for direct checkout and remembers its
	 * {@link CheckoutMetadata}.
	 *
	 * @param path
	 *            of the entry
	 * @param entry
	 *            to add
	 * @param attributes
	 *            the {@link Attributes} of the trees
	 * @throws IOException
	 *             if the {@link CheckoutMetadata} cannot be determined
	 * @since 6.1
	 */
	protected void addToCheckout(String path, DirCacheEntry entry,
			Attributes[] attributes)
			throws IOException {
		EolStreamType cleanupStreamType = workTreeUpdater.detectCheckoutStreamType(attributes[T_OURS]);
		String cleanupSmudgeCommand = tw.getSmudgeCommand(attributes[T_OURS]);
		EolStreamType checkoutStreamType = workTreeUpdater.detectCheckoutStreamType(attributes[T_THEIRS]);
		String checkoutSmudgeCommand = tw.getSmudgeCommand(attributes[T_THEIRS]);
		workTreeUpdater.addToCheckout(path, entry, cleanupStreamType, cleanupSmudgeCommand,
				checkoutStreamType, checkoutSmudgeCommand);
	}

	/**
	 * Remember a path for deletion, and remember its {@link CheckoutMetadata}
	 * in case it has to be restored in the cleanUp.
	 *
	 * @param path
	 *            of the entry
	 * @param isFile
	 *            whether it is a file
	 * @param attributes
	 *            to use for determining the {@link CheckoutMetadata}
	 * @throws IOException
	 *             if the {@link CheckoutMetadata} cannot be determined
	 * @since 5.1
	 */
	protected void addDeletion(String path, boolean isFile,
			Attributes attributes) throws IOException {
		if (db == null || nonNullRepo().isBare() || !isFile)
			return;

		File file = new File(nonNullRepo().getWorkTree(), path);
		EolStreamType streamType = workTreeUpdater.detectCheckoutStreamType(attributes);
		String smudgeCommand = tw.getSmudgeCommand(attributes);
		workTreeUpdater.deleteFile(path, file, streamType, smudgeCommand);
	}

	/**
	 * Processes one path and tries to merge taking git attributes in account.
	 * This method will do all trivial (not content) merges and will also detect
	 * if a merge will fail. The merge will fail when one of the following is
	 * true
	 * <ul>
	 * <li>the index entry does not match the entry in ours. When merging one
	 * branch into the current HEAD, ours will point to HEAD and theirs will
	 * point to the other branch. It is assumed that the index matches the HEAD
	 * because it will only not match HEAD if it was populated before the merge
	 * operation. But the merge commit should not accidentally contain
	 * modifications done before the merge. Check the <a href=
	 * "http://www.kernel.org/pub/software/scm/git/docs/git-read-tree.html#_3_way_merge"
	 * >git read-tree</a> documentation for further explanations.</li>
	 * <li>A conflict was detected and the working-tree file is dirty. When a
	 * conflict is detected the content-merge algorithm will try to write a
	 * merged version into the working-tree. If the file is dirty we would
	 * override unsaved data.</li>
	 * </ul>
	 *
	 * @param base
	 *            the common base for ours and theirs
	 * @param ours
	 *            the ours side of the merge. When merging a branch into the
	 *            HEAD ours will point to HEAD
	 * @param theirs
	 *            the theirs side of the merge. When merging a branch into the
	 *            current HEAD theirs will point to the branch which is merged
	 *            into HEAD.
	 * @param index
	 *            the index entry
	 * @param work
	 *            the file in the working tree
	 * @param ignoreConflicts
	 *            see
	 *            {@link org.eclipse.jgit.merge.ResolveMerger#mergeTrees(AbstractTreeIterator, RevTree, RevTree, boolean)}
	 * @param attributes
	 *            the {@link Attributes} for the three trees
	 * @return <code>false</code> if the merge will fail because the index entry
	 *         didn't match ours or the working-dir file was dirty and a
	 *         conflict occurred
	 * @throws java.io.IOException
	 * @since 6.1
	 */
	protected boolean processEntry(CanonicalTreeParser base,
			CanonicalTreeParser ours, CanonicalTreeParser theirs,
			DirCacheBuildIterator index, WorkingTreeIterator work,
			boolean ignoreConflicts, Attributes[] attributes)
			throws IOException {
		enterSubtree = true;
		final int modeO = tw.getRawMode(T_OURS);
		final int modeT = tw.getRawMode(T_THEIRS);
		final int modeB = tw.getRawMode(T_BASE);
		boolean gitLinkMerging = isGitLink(modeO) || isGitLink(modeT)
				|| isGitLink(modeB);
		if (modeO == 0 && modeT == 0 && modeB == 0) {
			// File is either untracked or new, staged but uncommitted
			return true;
		}

		if (isIndexDirty()) {
			return false;
		}

		DirCacheEntry ourDce = null;

		if (index == null || index.getDirCacheEntry() == null) {
			// create a fake DCE, but only if ours is valid. ours is kept only
			// in case it is valid, so a null ourDce is ok in all other cases.
			if (nonTree(modeO)) {
				ourDce = new DirCacheEntry(tw.getRawPath());
				ourDce.setObjectId(tw.getObjectId(T_OURS));
				ourDce.setFileMode(tw.getFileMode(T_OURS));
			}
		} else {
			ourDce = index.getDirCacheEntry();
		}

		if (nonTree(modeO) && nonTree(modeT) && tw.idEqual(T_OURS, T_THEIRS)) {
			// OURS and THEIRS have equal content. Check the file mode
			if (modeO == modeT) {
				// content and mode of OURS and THEIRS are equal: it doesn't
				// matter which one we choose. OURS is chosen. Since the index
				// is clean (the index matches already OURS) we can keep the existing one
				keep(ourDce);
				// no checkout needed!
				return true;
			}
			// same content but different mode on OURS and THEIRS.
			// Try to merge the mode and report an error if this is
			// not possible.
			int newMode = mergeFileModes(modeB, modeO, modeT);
			if (newMode != FileMode.MISSING.getBits()) {
				if (newMode == modeO) {
					// ours version is preferred
					keep(ourDce);
				} else {
					// the preferred version THEIRS has a different mode
					// than ours. Check it out!
					if (isWorktreeDirty(work, ourDce)) {
						return false;
					}
					// we know about length and lastMod only after we have
					// written the new content.
					// This will happen later. Set these values to 0 for know.
					DirCacheEntry e = add(tw.getRawPath(), theirs,
							DirCacheEntry.STAGE_0, EPOCH, 0);
					addToCheckout(tw.getPathString(), e, attributes);
				}
				return true;
			}
			if (!ignoreConflicts) {
				// FileModes are not mergeable. We found a conflict on modes.
				// For conflicting entries we don't know lastModified and
				// length.
				// This path can be skipped on ignoreConflicts, so the caller
				// could use virtual commit.
				add(tw.getRawPath(), base, DirCacheEntry.STAGE_1, EPOCH, 0);
				add(tw.getRawPath(), ours, DirCacheEntry.STAGE_2, EPOCH, 0);
				add(tw.getRawPath(), theirs, DirCacheEntry.STAGE_3, EPOCH, 0);
				unmergedPaths.add(tw.getPathString());
				mergeResults.put(tw.getPathString(),
						new MergeResult<>(Collections.emptyList()));
			}
			return true;
		}

		if (modeB == modeT && tw.idEqual(T_BASE, T_THEIRS)) {
			// THEIRS was not changed compared to BASE. All changes must be in
			// OURS. OURS is chosen. We can keep the existing entry.
			if (ourDce != null) {
				keep(ourDce);
			}
			// no checkout needed!
			return true;
		}

		if (modeB == modeO && tw.idEqual(T_BASE, T_OURS)) {
			// OURS was not changed compared to BASE. All changes must be in
			// THEIRS. THEIRS is chosen.

			// Check worktree before checking out THEIRS
			if (isWorktreeDirty(work, ourDce)) {
				return false;
			}
			if (nonTree(modeT)) {
				// we know about length and lastMod only after we have written
				// the new content.
				// This will happen later. Set these values to 0 for know.
				DirCacheEntry e = add(tw.getRawPath(), theirs,
						DirCacheEntry.STAGE_0, EPOCH, 0);
				if (e != null) {
					addToCheckout(tw.getPathString(), e, attributes);
				}
				return true;
			}
			// we want THEIRS ... but THEIRS contains a folder or the
			// deletion of the path. Delete what's in the working tree,
			// which we know to be clean.
			if (tw.getTreeCount() > T_FILE && tw.getRawMode(T_FILE) == 0) {
				// Not present in working tree, so nothing to delete
				return true;
			}
			if (modeT != 0 && modeT == modeB) {
				// Base, ours, and theirs all contain a folder: don't delete
				return true;
			}
			addDeletion(tw.getPathString(), nonTree(modeO), attributes[T_OURS]);
			return true;
		}

		if (tw.isSubtree()) {
			// file/folder conflicts: here I want to detect only file/folder
			// conflict between ours and theirs. file/folder conflicts between
			// base/index/workingTree and something else are not relevant or
			// detected later
			if (nonTree(modeO) != nonTree(modeT)) {
				if (ignoreConflicts) {
					// In case of merge failures, ignore this path instead of reporting unmerged, so
					// a caller can use virtual commit. This will not result in files with conflict
					// markers in the index/working tree. The actual diff on the path will be
					// computed directly on children.
					enterSubtree = false;
					return true;
				}
				if (nonTree(modeB)) {
					add(tw.getRawPath(), base, DirCacheEntry.STAGE_1, EPOCH, 0);
				}
				if (nonTree(modeO)) {
					add(tw.getRawPath(), ours, DirCacheEntry.STAGE_2, EPOCH, 0);
				}
				if (nonTree(modeT)) {
					add(tw.getRawPath(), theirs, DirCacheEntry.STAGE_3, EPOCH, 0);
				}
				unmergedPaths.add(tw.getPathString());
				enterSubtree = false;
				return true;
			}

			// ours and theirs are both folders or both files (and treewalk
			// tells us we are in a subtree because of index or working-dir).
			// If they are both folders no content-merge is required - we can
			// return here.
			if (!nonTree(modeO)) {
				return true;
			}

			// ours and theirs are both files, just fall out of the if block
			// and do the content merge
		}

		if (nonTree(modeO) && nonTree(modeT)) {
			// Check worktree before modifying files
			boolean worktreeDirty = isWorktreeDirty(work, ourDce);
			if (!attributes[T_OURS].canBeContentMerged() && worktreeDirty) {
				return false;
			}

			if (gitLinkMerging && ignoreConflicts) {
				// Always select 'ours' in case of GITLINK merge failures so
				// a caller can use virtual commit.
				add(tw.getRawPath(), ours, DirCacheEntry.STAGE_0, EPOCH, 0);
				return true;
			} else if (gitLinkMerging) {
				add(tw.getRawPath(), base, DirCacheEntry.STAGE_1, EPOCH, 0);
				add(tw.getRawPath(), ours, DirCacheEntry.STAGE_2, EPOCH, 0);
				add(tw.getRawPath(), theirs, DirCacheEntry.STAGE_3, EPOCH, 0);
				MergeResult<SubmoduleConflict> result = createGitLinksMergeResult(
						base, ours, theirs);
				result.setContainsConflicts(true);
				mergeResults.put(tw.getPathString(), result);
				unmergedPaths.add(tw.getPathString());
				return true;
			} else if (!attributes[T_OURS].canBeContentMerged()) {
				// File marked as binary
				switch (getContentMergeStrategy()) {
					case OURS:
						keep(ourDce);
						return true;
					case THEIRS:
						DirCacheEntry theirEntry = add(tw.getRawPath(), theirs,
								DirCacheEntry.STAGE_0, EPOCH, 0);
						addToCheckout(tw.getPathString(), theirEntry, attributes);
						return true;
					default:
						break;
				}
				add(tw.getRawPath(), base, DirCacheEntry.STAGE_1, EPOCH, 0);
				add(tw.getRawPath(), ours, DirCacheEntry.STAGE_2, EPOCH, 0);
				add(tw.getRawPath(), theirs, DirCacheEntry.STAGE_3, EPOCH, 0);

				// attribute merge issues are conflicts but not failures
				unmergedPaths.add(tw.getPathString());
				return true;
			}

			// Check worktree before modifying files
			if (worktreeDirty) {
				return false;
			}

			MergeResult<RawText> result = null;
			try {
				result = contentMerge(base, ours, theirs, attributes,
						getContentMergeStrategy());
			} catch (BinaryBlobException e) {
				switch (getContentMergeStrategy()) {
					case OURS:
						keep(ourDce);
						return true;
					case THEIRS:
						DirCacheEntry theirEntry = add(tw.getRawPath(), theirs,
								DirCacheEntry.STAGE_0, EPOCH, 0);
						addToCheckout(tw.getPathString(), theirEntry, attributes);
						return true;
					default:
						result = new MergeResult<>(Collections.emptyList());
						result.setContainsConflicts(true);
						break;
				}
			}
			if (ignoreConflicts) {
				result.setContainsConflicts(false);
			}
			updateIndex(base, ours, theirs, result, attributes[T_OURS]);
			String currentPath = tw.getPathString();
			if (result.containsConflicts() && !ignoreConflicts) {
				unmergedPaths.add(currentPath);
			}
			workTreeUpdater.markAsModified(currentPath);
			// Entry is null - only adds the metadata.
			addToCheckout(currentPath, null, attributes);
		} else if (modeO != modeT) {
			// OURS or THEIRS has been deleted
			if (((modeO != 0 && !tw.idEqual(T_BASE, T_OURS)) || (modeT != 0 && !tw
					.idEqual(T_BASE, T_THEIRS)))) {
				if (gitLinkMerging && ignoreConflicts) {
					add(tw.getRawPath(), ours, DirCacheEntry.STAGE_0, EPOCH, 0);
				} else if (gitLinkMerging) {
					add(tw.getRawPath(), base, DirCacheEntry.STAGE_1, EPOCH, 0);
					add(tw.getRawPath(), ours, DirCacheEntry.STAGE_2, EPOCH, 0);
					add(tw.getRawPath(), theirs, DirCacheEntry.STAGE_3, EPOCH, 0);
					MergeResult<SubmoduleConflict> result = createGitLinksMergeResult(
							base, ours, theirs);
					result.setContainsConflicts(true);
					mergeResults.put(tw.getPathString(), result);
					unmergedPaths.add(tw.getPathString());
				} else {
					// Content merge strategy does not apply to delete-modify
					// conflicts!
					MergeResult<RawText> result;
					try {
						result = contentMerge(base, ours, theirs, attributes,
								ContentMergeStrategy.CONFLICT);
					} catch (BinaryBlobException e) {
						result = new MergeResult<>(Collections.emptyList());
						result.setContainsConflicts(true);
					}
					if (ignoreConflicts) {
						// In case a conflict is detected the working tree file
						// is again filled with new content (containing conflict
						// markers). But also stage 0 of the index is filled
						// with that content.
						result.setContainsConflicts(false);
						updateIndex(base, ours, theirs, result,
								attributes[T_OURS]);
					} else {
						add(tw.getRawPath(), base, DirCacheEntry.STAGE_1, EPOCH,
								0);
						add(tw.getRawPath(), ours, DirCacheEntry.STAGE_2, EPOCH,
								0);
						DirCacheEntry e = add(tw.getRawPath(), theirs,
								DirCacheEntry.STAGE_3, EPOCH, 0);

						// OURS was deleted checkout THEIRS
						if (modeO == 0) {
							// Check worktree before checking out THEIRS
							if (isWorktreeDirty(work, ourDce)) {
								return false;
							}
							if (nonTree(modeT) && e != null) {
								addToCheckout(tw.getPathString(), e,
										attributes);
							}
						}

						unmergedPaths.add(tw.getPathString());

						// generate a MergeResult for the deleted file
						mergeResults.put(tw.getPathString(), result);
					}
				}
			}
		}
		return true;
	}

	private static MergeResult<SubmoduleConflict> createGitLinksMergeResult(
			CanonicalTreeParser base, CanonicalTreeParser ours,
			CanonicalTreeParser theirs) {
		return new MergeResult<>(Arrays.asList(
				new SubmoduleConflict(
						base == null ? null : base.getEntryObjectId()),
				new SubmoduleConflict(
						ours == null ? null : ours.getEntryObjectId()),
				new SubmoduleConflict(
						theirs == null ? null : theirs.getEntryObjectId())));
	}

	/**
	 * Does the content merge. The three texts base, ours and theirs are
	 * specified with {@link CanonicalTreeParser}. If any of the parsers is
	 * specified as <code>null</code> then an empty text will be used instead.
	 *
	 * @param base
	 * @param ours
	 * @param theirs
	 * @param attributes
	 * @param strategy
	 *
	 * @return the result of the content merge
	 * @throws BinaryBlobException
	 *             if any of the blobs looks like a binary blob
	 * @throws IOException
	 */
	private MergeResult<RawText> contentMerge(CanonicalTreeParser base,
			CanonicalTreeParser ours, CanonicalTreeParser theirs,
			Attributes[] attributes, ContentMergeStrategy strategy)
			throws BinaryBlobException, IOException {
		// TW: The attributes here are used to determine the LFS smudge filter.
		// Is doing a content merge on LFS items really a good idea??
		RawText baseText = base == null ? RawText.EMPTY_TEXT
				: getRawText(base.getEntryObjectId(), attributes[T_BASE]);
		RawText ourText = ours == null ? RawText.EMPTY_TEXT
				: getRawText(ours.getEntryObjectId(), attributes[T_OURS]);
		RawText theirsText = theirs == null ? RawText.EMPTY_TEXT
				: getRawText(theirs.getEntryObjectId(), attributes[T_THEIRS]);
		mergeAlgorithm.setContentMergeStrategy(strategy);
		return mergeAlgorithm.merge(RawTextComparator.DEFAULT, baseText,
				ourText, theirsText);
	}

	private boolean isIndexDirty() {
		if (inCore) {
			return false;
		}

		final int modeI = tw.getRawMode(T_INDEX);
		final int modeO = tw.getRawMode(T_OURS);

		// Index entry has to match ours to be considered clean
		final boolean isDirty = nonTree(modeI)
				&& !(modeO == modeI && tw.idEqual(T_INDEX, T_OURS));
		if (isDirty) {
			failingPaths
					.put(tw.getPathString(), MergeFailureReason.DIRTY_INDEX);
		}
		return isDirty;
	}

	private boolean isWorktreeDirty(WorkingTreeIterator work,
			DirCacheEntry ourDce) throws IOException {
		if (work == null) {
			return false;
		}

		final int modeF = tw.getRawMode(T_FILE);
		final int modeO = tw.getRawMode(T_OURS);

		// Worktree entry has to match ours to be considered clean
		boolean isDirty;
		if (ourDce != null) {
			isDirty = work.isModified(ourDce, true, reader);
		} else {
			isDirty = work.isModeDifferent(modeO);
			if (!isDirty && nonTree(modeF)) {
				isDirty = !tw.idEqual(T_FILE, T_OURS);
			}
		}

		// Ignore existing empty directories
		if (isDirty && modeF == FileMode.TYPE_TREE
				&& modeO == FileMode.TYPE_MISSING) {
			isDirty = false;
		}
		if (isDirty) {
			failingPaths.put(tw.getPathString(),
					MergeFailureReason.DIRTY_WORKTREE);
		}
		return isDirty;
	}

	/**
	 * Updates the index after a content merge has happened. If no conflict has
	 * occurred this includes persisting the merged content to the object
	 * database. In case of conflicts this method takes care to write the
	 * correct stages to the index.
	 *
	 * @param base
	 * @param ours
	 * @param theirs
	 * @param result
	 * @param attributes
	 * @throws IOException
	 */
	private void updateIndex(CanonicalTreeParser base,
			CanonicalTreeParser ours, CanonicalTreeParser theirs,
			MergeResult<RawText> result, Attributes attributes)
			throws IOException {
		TemporaryBuffer rawMerged = null;
		try {
			rawMerged = doMerge(result);
			File mergedFile = inCore ? null
					: writeMergedFile(rawMerged, attributes);
			if (result.containsConflicts()) {
				// A conflict occurred, the file will contain conflict markers
				// the index will be populated with the three stages and the
				// workdir (if used) contains the halfway merged content.
				add(tw.getRawPath(), base, DirCacheEntry.STAGE_1, EPOCH, 0);
				add(tw.getRawPath(), ours, DirCacheEntry.STAGE_2, EPOCH, 0);
				add(tw.getRawPath(), theirs, DirCacheEntry.STAGE_3, EPOCH, 0);
				mergeResults.put(tw.getPathString(), result);
				return;
			}

			// No conflict occurred, the file will contain fully merged content.
			// The index will be populated with the new merged version.
			Instant lastModified =
					mergedFile == null ? null : nonNullRepo().getFS().lastModifiedInstant(mergedFile);
			// Set the mode for the new content. Fall back to REGULAR_FILE if
			// we can't merge modes of OURS and THEIRS.
			int newMode = mergeFileModes(tw.getRawMode(0), tw.getRawMode(1),
					tw.getRawMode(2));
			FileMode mode = newMode == FileMode.MISSING.getBits()
					? FileMode.REGULAR_FILE : FileMode.fromBits(newMode);
			workTreeUpdater.insertToIndex(rawMerged.openInputStream(), tw.getPathString().getBytes(UTF_8), mode,
					DirCacheEntry.STAGE_0, lastModified, (int) rawMerged.length(),
					attributes.get(Constants.ATTR_MERGE));
		} finally {
			if (rawMerged != null) {
				rawMerged.destroy();
			}
		}
	}

	/**
	 * Writes merged file content to the working tree.
	 *
	 * @param rawMerged
	 *            the raw merged content
	 * @param attributes
	 *            the files .gitattributes entries
	 * @return the working tree file to which the merged content was written.
	 * @throws IOException
	 */
	private File writeMergedFile(TemporaryBuffer rawMerged,
			Attributes attributes)
			throws IOException {
		File workTree = nonNullRepo().getWorkTree();
		FS fs = nonNullRepo().getFS();
		File of = new File(workTree, tw.getPathString());
		File parentFolder = of.getParentFile();
		EolStreamType eol = workTreeUpdater.detectCheckoutStreamType(attributes);
		if (!fs.exists(parentFolder)) {
			parentFolder.mkdirs();
		}
		StreamLoader contentLoader = WorkTreeUpdater.createStreamLoader(rawMerged::openInputStream,
				rawMerged.length());
		workTreeUpdater.updateFileWithContent(contentLoader,
				eol, tw.getSmudgeCommand(attributes), of.getPath(), of, false);
		return of;
	}

	private TemporaryBuffer doMerge(MergeResult<RawText> result)
			throws IOException {
		TemporaryBuffer.LocalFile buf = new TemporaryBuffer.LocalFile(
				db != null ? nonNullRepo().getDirectory() : null, workTreeUpdater.getInCoreFileSizeLimit());
		boolean success = false;
		try {
			new MergeFormatter().formatMerge(buf, result,
					Arrays.asList(commitNames), UTF_8);
			buf.close();
			success = true;
		} finally {
			if (!success) {
				buf.destroy();
			}
		}
		return buf;
	}

	/**
	 * Try to merge filemodes. If only ours or theirs have changed the mode
	 * (compared to base) we choose that one. If ours and theirs have equal
	 * modes return that one. If also that is not the case the modes are not
	 * mergeable. Return {@link FileMode#MISSING} int that case.
	 *
	 * @param modeB
	 *            filemode found in BASE
	 * @param modeO
	 *            filemode found in OURS
	 * @param modeT
	 *            filemode found in THEIRS
	 *
	 * @return the merged filemode or {@link FileMode#MISSING} in case of a
	 *         conflict
	 */
	private int mergeFileModes(int modeB, int modeO, int modeT) {
		if (modeO == modeT) {
			return modeO;
		}
		if (modeB == modeO) {
			// Base equal to Ours -> chooses Theirs if that is not missing
			return (modeT == FileMode.MISSING.getBits()) ? modeO : modeT;
		}
		if (modeB == modeT) {
			// Base equal to Theirs -> chooses Ours if that is not missing
			return (modeO == FileMode.MISSING.getBits()) ? modeT : modeO;
		}
		return FileMode.MISSING.getBits();
	}

	private RawText getRawText(ObjectId id,
			Attributes attributes)
			throws IOException, BinaryBlobException {
		if (id.equals(ObjectId.zeroId())) {
			return new RawText(new byte[]{});
		}

		ObjectLoader loader = LfsFactory.getInstance().applySmudgeFilter(
				getRepository(), reader.open(id, OBJ_BLOB),
				attributes.get(Constants.ATTR_MERGE));
		int threshold = PackConfig.DEFAULT_BIG_FILE_THRESHOLD;
		return RawText.load(loader, threshold);
	}

	private static boolean nonTree(int mode) {
		return mode != 0 && !FileMode.TREE.equals(mode);
	}

	private static boolean isGitLink(int mode) {
		return FileMode.GITLINK.equals(mode);
	}

	/** {@inheritDoc} */
	@Override
	public ObjectId getResultTreeId() {
		return (resultTree == null) ? null : resultTree.toObjectId();
	}

	/**
	 * Set the names of the commits as they would appear in conflict markers
	 *
	 * @param commitNames
	 *            the names of the commits as they would appear in conflict
	 *            markers
	 */
	public void setCommitNames(String[] commitNames) {
		this.commitNames = commitNames;
	}

	/**
	 * Get the names of the commits as they would appear in conflict markers.
	 *
	 * @return the names of the commits as they would appear in conflict
	 *         markers.
	 */
	public String[] getCommitNames() {
		return commitNames;
	}

	/**
	 * Get the paths with conflicts. This is a subset of the files listed by
	 * {@link #getModifiedFiles()}
	 *
	 * @return the paths with conflicts. This is a subset of the files listed by
	 *         {@link #getModifiedFiles()}
	 */
	public List<String> getUnmergedPaths() {
		return unmergedPaths;
	}

	/**
	 * Get the paths of files which have been modified by this merge.
	 *
	 * @return the paths of files which have been modified by this merge. A file
	 *         will be modified if a content-merge works on this path or if the
	 *         merge algorithm decides to take the theirs-version. This is a
	 *         superset of the files listed by {@link #getUnmergedPaths()}.
	 */
	public List<String> getModifiedFiles() {
		return workTreeUpdater != null ? workTreeUpdater.getModifiedFiles() : modifiedFiles;
	}

	/**
	 * Get a map which maps the paths of files which have to be checked out
	 * because the merge created new fully-merged content for this file into the
	 * index.
	 *
	 * @return a map which maps the paths of files which have to be checked out
	 *         because the merge created new fully-merged content for this file
	 *         into the index. This means: the merge wrote a new stage 0 entry
	 *         for this path.
	 */
	public Map<String, DirCacheEntry> getToBeCheckedOut() {
		return workTreeUpdater.getToBeCheckedOut();
	}

	/**
	 * Get the mergeResults
	 *
	 * @return the mergeResults
	 */
	public Map<String, MergeResult<? extends Sequence>> getMergeResults() {
		return mergeResults;
	}

	/**
	 * Get list of paths causing this merge to fail (not stopped because of a
	 * conflict).
	 *
	 * @return lists paths causing this merge to fail (not stopped because of a
	 *         conflict). <code>null</code> is returned if this merge didn't
	 *         fail.
	 */
	public Map<String, MergeFailureReason> getFailingPaths() {
		return failingPaths.isEmpty() ? null : failingPaths;
	}

	/**
	 * Returns whether this merge failed (i.e. not stopped because of a
	 * conflict)
	 *
	 * @return <code>true</code> if a failure occurred, <code>false</code>
	 *         otherwise
	 */
	public boolean failed() {
		return !failingPaths.isEmpty();
	}

	/**
	 * Sets the DirCache which shall be used by this merger. If the DirCache is
	 * not set explicitly and if this merger doesn't work in-core, this merger
	 * will implicitly get and lock a default DirCache. If the DirCache is
	 * explicitly set the caller is responsible to lock it in advance. Finally
	 * the merger will call {@link org.eclipse.jgit.dircache.DirCache#commit()}
	 * which requires that the DirCache is locked. If the {@link #mergeImpl()}
	 * returns without throwing an exception the lock will be released. In case
	 * of exceptions the caller is responsible to release the lock.
	 *
	 * @param dc
	 *            the DirCache to set
	 */
	public void setDirCache(DirCache dc) {
		this.dircache = dc;
	}

	/**
	 * Sets the WorkingTreeIterator to be used by this merger. If no
	 * WorkingTreeIterator is set this merger will ignore the working tree and
	 * fail if a content merge is necessary.
	 * <p>
	 * TODO: enhance WorkingTreeIterator to support write operations. Then this
	 * merger will be able to merge with a different working tree abstraction.
	 *
	 * @param workingTreeIterator
	 *            the workingTreeIt to set
	 */
	public void setWorkingTreeIterator(WorkingTreeIterator workingTreeIterator) {
		this.workingTreeIterator = workingTreeIterator;
	}


	/**
	 * The resolve conflict way of three way merging
	 *
	 * @param baseTree
	 *            a {@link org.eclipse.jgit.treewalk.AbstractTreeIterator}
	 *            object.
	 * @param headTree
	 *            a {@link org.eclipse.jgit.revwalk.RevTree} object.
	 * @param mergeTree
	 *            a {@link org.eclipse.jgit.revwalk.RevTree} object.
	 * @param ignoreConflicts
	 *            Controls what to do in case a content-merge is done and a
	 *            conflict is detected. The default setting for this should be
	 *            <code>false</code>. In this case the working tree file is
	 *            filled with new content (containing conflict markers) and the
	 *            index is filled with multiple stages containing BASE, OURS and
	 *            THEIRS content. Having such non-0 stages is the sign to git
	 *            tools that there are still conflicts for that path.
	 *            <p>
	 *            If <code>true</code> is specified the behavior is different.
	 *            In case a conflict is detected the working tree file is again
	 *            filled with new content (containing conflict markers). But
	 *            also stage 0 of the index is filled with that content. No
	 *            other stages are filled. Means: there is no conflict on that
	 *            path but the new content (including conflict markers) is
	 *            stored as successful merge result. This is needed in the
	 *            context of {@link org.eclipse.jgit.merge.RecursiveMerger}
	 *            where when determining merge bases we don't want to deal with
	 *            content-merge conflicts.
	 * @return whether the trees merged cleanly
	 * @throws java.io.IOException
	 * @since 3.5
	 */
	protected boolean mergeTrees(AbstractTreeIterator baseTree,
			RevTree headTree, RevTree mergeTree, boolean ignoreConflicts)
			throws IOException {
		try {
			workTreeUpdater = inCore ?
					WorkTreeUpdater.createInCoreWorkTreeUpdater(db, dircache, getObjectInserter()) :
					WorkTreeUpdater.createWorkTreeUpdater(db, dircache);
			dircache = workTreeUpdater.getLockedDirCache();
			tw = new NameConflictTreeWalk(db, reader);

			tw.addTree(baseTree);
			tw.setHead(tw.addTree(headTree));
			tw.addTree(mergeTree);
			DirCacheBuildIterator buildIt = workTreeUpdater.createDirCacheBuildIterator();
			int dciPos = tw.addTree(buildIt);
			if (workingTreeIterator != null) {
				tw.addTree(workingTreeIterator);
				workingTreeIterator.setDirCacheIterator(tw, dciPos);
			} else {
				tw.setFilter(TreeFilter.ANY_DIFF);
			}

			if (!mergeTreeWalk(tw, ignoreConflicts)) {
				return false;
			}

			workTreeUpdater.writeWorkTreeChanges(true);
			if (getUnmergedPaths().isEmpty() && !failed()) {
				WorkTreeUpdater.Result result = workTreeUpdater.writeIndexChanges();
				resultTree = result.treeId;
				modifiedFiles = result.modifiedFiles;
				for (String f : result.failedToDelete) {
					failingPaths.put(f, MergeFailureReason.COULD_NOT_DELETE);
				}
				return result.failedToDelete.isEmpty();
			}
			resultTree = null;
			return false;
		} finally {
			if(modifiedFiles.isEmpty()) {
				modifiedFiles = workTreeUpdater.getModifiedFiles();
			}
			workTreeUpdater.close();
			workTreeUpdater = null;
		}
	}

	/**
	 * Process the given TreeWalk's entries.
	 *
	 * @param treeWalk
	 *            The walk to iterate over.
	 * @param ignoreConflicts
	 *            see
	 *            {@link org.eclipse.jgit.merge.ResolveMerger#mergeTrees(AbstractTreeIterator, RevTree, RevTree, boolean)}
	 * @return Whether the trees merged cleanly.
	 * @throws java.io.IOException
	 * @since 3.5
	 */
	protected boolean mergeTreeWalk(TreeWalk treeWalk, boolean ignoreConflicts)
			throws IOException {
		boolean hasWorkingTreeIterator = tw.getTreeCount() > T_FILE;
		boolean hasAttributeNodeProvider = treeWalk
				.getAttributesNodeProvider() != null;
		while (treeWalk.next()) {
			Attributes[] attributes = {NO_ATTRIBUTES, NO_ATTRIBUTES,
					NO_ATTRIBUTES};
			if (hasAttributeNodeProvider) {
				attributes[T_BASE] = treeWalk.getAttributes(T_BASE);
				attributes[T_OURS] = treeWalk.getAttributes(T_OURS);
				attributes[T_THEIRS] = treeWalk.getAttributes(T_THEIRS);
			}
			if (!processEntry(
					treeWalk.getTree(T_BASE, CanonicalTreeParser.class),
					treeWalk.getTree(T_OURS, CanonicalTreeParser.class),
					treeWalk.getTree(T_THEIRS, CanonicalTreeParser.class),
					treeWalk.getTree(T_INDEX, DirCacheBuildIterator.class),
					hasWorkingTreeIterator ? treeWalk.getTree(T_FILE,
							WorkingTreeIterator.class) : null,
					ignoreConflicts, attributes)) {
				workTreeUpdater.revertModifiedFiles();
				return false;
			}
			if (treeWalk.isSubtree() && enterSubtree) {
				treeWalk.enterSubtree();
			}
		}
		return true;
	}
}
