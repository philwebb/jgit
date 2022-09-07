/*
 * Copyright (C) 2018 Ericsson and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.file;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.time.Instant;

import static org.eclipse.jgit.lib.Constants.REFS;
import static org.eclipse.jgit.lib.Constants.HEADS;

import org.junit.Before;
import org.junit.Test;

public class GcDeleteEmptyRefsFoldersTest extends GcTestCase {
	private static final String REF_FOLDER_01 = "A/B/01";
	private static final String REF_FOLDER_02 = "C/D/02";

	private Path refsDir;
	private Path heads;

	@Override
	@Before
	public void setUp() throws Exception {
		super.setUp();
		refsDir = Paths.get(repo.getDirectory().getAbsolutePath())
				.resolve(REFS);
		heads = refsDir.resolve(HEADS);
	}

	@Test
	public void emptyRefFoldersAreDeleted() throws Exception {
		FileTime fileTime = FileTime.from(Instant.now().minusSeconds(31));
		Path refDir01 = Files.createDirectories(heads.resolve(REF_FOLDER_01));
		Path refDir02 = Files.createDirectories(heads.resolve(REF_FOLDER_02));
		setLastModifiedTime(fileTime, heads, REF_FOLDER_01);
		setLastModifiedTime(fileTime, heads, REF_FOLDER_02);
		assertTrue(refDir01.toFile().exists());
		assertTrue(refDir02.toFile().exists());
		gc.gc().get();

		assertFalse(refDir01.toFile().exists());
		assertFalse(refDir01.getParent().toFile().exists());
		assertFalse(refDir01.getParent().getParent().toFile().exists());
		assertFalse(refDir02.toFile().exists());
		assertFalse(refDir02.getParent().toFile().exists());
		assertFalse(refDir02.getParent().getParent().toFile().exists());
	}

	@Test
	public void emptyRefFoldersSkipFiles() throws Exception {
		FileTime fileTime = FileTime.from(Instant.now().minusSeconds(31));
		Path refFile = Files.createFile(refsDir.resolve(".DS_Store"));
		Path refDir01 = Files.createDirectories(heads.resolve(REF_FOLDER_01));
		Path refDir02 = Files.createDirectories(heads.resolve(REF_FOLDER_02));
		setLastModifiedTime(fileTime, heads, REF_FOLDER_01);
		setLastModifiedTime(fileTime, heads, REF_FOLDER_02);
		assertTrue(refDir01.toFile().exists());
		assertTrue(refDir02.toFile().exists());
		gc.gc().get();
		assertTrue(Files.exists(refFile));
	}

	private void setLastModifiedTime(FileTime fileTime, Path path, String folder) throws IOException {
		long numParents = folder.chars().filter(c -> c == '/').count();
		Path folderPath = path.resolve(folder);
		for(int folderLevel = 0; folderLevel <= numParents; folderLevel ++ ) {
			Files.setLastModifiedTime(folderPath, fileTime);
			folderPath = folderPath.getParent();
		}
	}

	@Test
	public void emptyRefFoldersAreKeptIfTheyAreTooRecent()
			throws Exception {
		Path refDir01 = Files.createDirectories(heads.resolve(REF_FOLDER_01));
		Path refDir02 = Files.createDirectories(heads.resolve(REF_FOLDER_02));
		assertTrue(refDir01.toFile().exists());
		assertTrue(refDir02.toFile().exists());
		gc.gc().get();

		assertTrue(refDir01.toFile().exists());
		assertTrue(refDir02.toFile().exists());
	}

	@Test
	public void nonEmptyRefsFoldersAreKept() throws Exception {
		Path refDir01 = Files.createDirectories(heads.resolve(REF_FOLDER_01));
		Path refDir02 = Files.createDirectories(heads.resolve(REF_FOLDER_02));
		Path ref01 = Files.createFile(refDir01.resolve("ref01"));
		Path ref02 = Files.createFile(refDir01.resolve("ref02"));
		assertTrue(refDir01.toFile().exists());
		assertTrue(refDir02.toFile().exists());
		assertTrue(ref01.toFile().exists());
		assertTrue(ref02.toFile().exists());
		gc.gc().get();
		assertTrue(refDir01.toFile().exists());
		assertTrue(refDir02.toFile().exists());
		assertTrue(ref01.toFile().exists());
		assertTrue(ref02.toFile().exists());
	}
}