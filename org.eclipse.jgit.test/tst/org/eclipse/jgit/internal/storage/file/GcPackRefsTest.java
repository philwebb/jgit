/*
 * Copyright (C) 2012, Christian Halstrick <christian.halstrick@sap.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.file;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.junit.TestRepository.BranchBuilder;
import org.eclipse.jgit.lib.ConfigConstants;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref.Storage;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.revwalk.RevBlob;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.junit.Test;

@SuppressWarnings("boxing")
public class GcPackRefsTest extends GcTestCase {
	@Test
	public void looseRefPacked() throws Exception {
		RevBlob a = tr.blob("a");
		tr.lightweightTag("t", a);

		gc.packRefs();
		assertSame(repo.exactRef("refs/tags/t").getStorage(), Storage.PACKED);
	}

	@Test
	public void emptyRefDirectoryDeleted() throws Exception {
		String ref = "dir/ref";
		tr.branch(ref).commit().create();
		String name = repo.findRef(ref).getName();
		Path dir = repo.getCommonDirectory().toPath().resolve(name).getParent();
		assertNotNull(dir);
		gc.packRefs();
		assertFalse(Files.exists(dir));
	}

	@Test
	public void concurrentOnlyOneWritesPackedRefs() throws Exception {
		RevBlob a = tr.blob("a");
		tr.lightweightTag("t", a);

		CyclicBarrier syncPoint = new CyclicBarrier(2);

		// Returns 0 for success, 1 in case of error when writing pack.
		Callable<Integer> packRefs = () -> {
			syncPoint.await();
			try {
				gc.packRefs();
				return 0;
			} catch (IOException e) {
				return 1;
			}
		};
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<Integer> p1 = pool.submit(packRefs);
			Future<Integer> p2 = pool.submit(packRefs);
			assertThat(p1.get() + p2.get(), lessThanOrEqualTo(1));
		} finally {
			pool.shutdown();
			pool.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
		}
	}

	@Test
	public void whileRefLockedRefNotPackedNoError()
			throws Exception {
		RevBlob a = tr.blob("a");
		tr.lightweightTag("t1", a);
		tr.lightweightTag("t2", a);
		LockFile refLock = new LockFile(new File(repo.getDirectory(),
				"refs/tags/t1"));
		try {
			refLock.lock();
			gc.packRefs();
		} finally {
			refLock.unlock();
		}

		assertSame(repo.exactRef("refs/tags/t1").getStorage(), Storage.LOOSE);
		assertSame(repo.exactRef("refs/tags/t2").getStorage(), Storage.PACKED);
	}

	@Test
	public void whileRefUpdatedRefUpdateSucceeds()
			throws Exception {
		RevBlob a = tr.blob("a");
		tr.lightweightTag("t", a);
		final RevBlob b = tr.blob("b");

		final CyclicBarrier refUpdateLockedRef = new CyclicBarrier(2);
		final CyclicBarrier packRefsDone = new CyclicBarrier(2);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<Result> result = pool.submit(() -> {
				RefUpdate update = new RefDirectoryUpdate(
						(RefDirectory) repo.getRefDatabase(),
						repo.exactRef("refs/tags/t")) {
					@Override
					public boolean isForceUpdate() {
						try {
							refUpdateLockedRef.await();
							packRefsDone.await();
						} catch (InterruptedException
								| BrokenBarrierException e) {
							Thread.currentThread().interrupt();
						}
						return super.isForceUpdate();
					}
				};
				update.setForceUpdate(true);
				update.setNewObjectId(b);
				return update.update();
			});

			Future<Result> result2 = pool.submit(() -> {
				refUpdateLockedRef.await();
				gc.packRefs();
				packRefsDone.await();
				return null;
			});
			assertNull(result2.get());

			assertSame(result.get(), Result.FORCED);

		} finally {
			pool.shutdownNow();
			pool.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
		}

		assertEquals(repo.exactRef("refs/tags/t").getObjectId(), b);
	}

	@Test
	public void dontPackHEAD_nonBare() throws Exception {
		BranchBuilder bb = tr.branch("refs/heads/side");
		RevCommit first = bb.commit().add("A", "A").add("B", "B").create();
		bb.commit().add("A", "A2").add("B", "B2").create();
		Git git = Git.wrap(repo);

		// check for the unborn branch master. HEAD should point to master and
		// master doesn't exist.
		assertEquals(repo.exactRef("HEAD").getTarget().getName(),
				"refs/heads/master");
		assertNull(repo.exactRef("HEAD").getTarget().getObjectId());
		gc.packRefs();
		assertSame(repo.exactRef("HEAD").getStorage(), Storage.LOOSE);
		assertEquals(repo.exactRef("HEAD").getTarget().getName(),
				"refs/heads/master");
		assertNull(repo.exactRef("HEAD").getTarget().getObjectId());

		git.checkout().setName("refs/heads/side").call();
		gc.packRefs();
		assertSame(repo.exactRef("HEAD").getStorage(), Storage.LOOSE);

		// check for detached HEAD
		git.checkout().setName(first.getName()).call();
		gc.packRefs();
		assertSame(repo.exactRef("HEAD").getStorage(), Storage.LOOSE);
	}

	@Test
	public void dontPackHEAD_bare() throws Exception {
		BranchBuilder bb = tr.branch("refs/heads/side");
		bb.commit().add("A", "A").add("B", "B").create();
		RevCommit second = bb.commit().add("A", "A2").add("B", "B2").create();

		// Convert the repo to be bare
		FileBasedConfig cfg = repo.getConfig();
		cfg.setBoolean(ConfigConstants.CONFIG_CORE_SECTION, null,
				ConfigConstants.CONFIG_KEY_BARE, true);
		cfg.save();
		Git git = Git.open(repo.getDirectory());
		repo = (FileRepository) git.getRepository();

		// check for the unborn branch master. HEAD should point to master and
		// master doesn't exist.
		assertEquals(repo.exactRef("HEAD").getTarget().getName(),
				"refs/heads/master");
		assertNull(repo.exactRef("HEAD").getTarget().getObjectId());
		gc.packRefs();
		assertSame(repo.exactRef("HEAD").getStorage(), Storage.LOOSE);
		assertEquals(repo.exactRef("HEAD").getTarget().getName(),
				"refs/heads/master");
		assertNull(repo.exactRef("HEAD").getTarget().getObjectId());

		// check for non-detached HEAD
		repo.updateRef(Constants.HEAD).link("refs/heads/side");
		gc.packRefs();
		assertSame(repo.exactRef("HEAD").getStorage(), Storage.LOOSE);
		assertEquals(repo.exactRef("HEAD").getTarget().getObjectId(),
				second.getId());
	}
}
