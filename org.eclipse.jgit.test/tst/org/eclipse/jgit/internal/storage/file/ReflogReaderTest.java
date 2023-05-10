/*
 * Copyright (C) 2009, Robin Rosenberg
 * Copyright (C) 2009, Robin Rosenberg <robin.rosenberg@dewire.com> and others
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Distribution License v. 1.0 which is available at
 * https://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 */

package org.eclipse.jgit.internal.storage.file;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.List;

import org.eclipse.jgit.lib.CheckoutEntry;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.ReflogEntry;
import org.eclipse.jgit.lib.ReflogReader;
import org.eclipse.jgit.test.resources.SampleDataRepositoryTestCase;
import org.junit.Test;

public class ReflogReaderTest extends SampleDataRepositoryTestCase {

	static byte[] oneLine = "da85355dfc525c9f6f3927b876f379f46ccf826e 3e7549db262d1e836d9bf0af7e22355468f1717c A O Thor Too <authortoo@wri.tr> 1243028200 +0200\tcommit: Add a toString for debugging to RemoteRefUpdate\n"
			.getBytes(UTF_8);

	static byte[] twoLine = ("0000000000000000000000000000000000000000 c6734895958052a9dbc396cff4459dc1a25029ab A U Thor <thor@committer.au> 1243028201 -0100\tbranch: Created from rr/renamebranchv4\n"
			+ "c6734895958052a9dbc396cff4459dc1a25029ab 54794942a18a237c57a80719afed44bb78172b10 Same A U Thor <same.author@example.com> 1243028202 +0100\trebase finished: refs/heads/rr/renamebranch5 onto c6e3b9fe2da0293f11eae202ec35fb343191a82d\n")
					.getBytes(UTF_8);

	static byte[] twoLineWithAppendInProgress = ("0000000000000000000000000000000000000000 c6734895958052a9dbc396cff4459dc1a25029ab A U Thor <thor@committer.au> 1243028201 -0100\tbranch: Created from rr/renamebranchv4\n"
			+ "c6734895958052a9dbc396cff4459dc1a25029ab 54794942a18a237c57a80719afed44bb78172b10 Same A U Thor <same.author@example.com> 1243028202 +0100\trebase finished: refs/heads/rr/renamebranch5 onto c6e3b9fe2da0293f11eae202ec35fb343191a82d\n"
			+ "54794942a18a237c57a80719afed44bb78172b10 ")
					.getBytes(UTF_8);

	static byte[] aLine = "1111111111111111111111111111111111111111 3e7549db262d1e836d9bf0af7e22355468f1717c A U Thor <thor@committer.au> 1243028201 -0100\tbranch: change to a\n"
			.getBytes(UTF_8);

	static byte[] masterLine = "2222222222222222222222222222222222222222 3e7549db262d1e836d9bf0af7e22355468f1717c A U Thor <thor@committer.au> 1243028201 -0100\tbranch: change to master\n"
			.getBytes(UTF_8);

	static byte[] headLine = "3333333333333333333333333333333333333333 3e7549db262d1e836d9bf0af7e22355468f1717c A U Thor <thor@committer.au> 1243028201 -0100\tbranch: change to HEAD\n"
			.getBytes(UTF_8);

	static byte[] oneLineWithoutComment = "da85355dfc525c9f6f3927b876f379f46ccf826e 3e7549db262d1e836d9bf0af7e22355468f1717c A O Thor Too <authortoo@wri.tr> 1243028200 +0200\n"
			.getBytes(UTF_8);

	static byte[] switchBranch = "0d43a6890a19fd657faad1c4cfbe3cb1b47851c3 4809df9c0d8bce5b00955563f77c5a9f25aa0d12 A O Thor Too <authortoo@wri.tr> 1315088009 +0200\tcheckout: moving from new/work to master\n"
			.getBytes(UTF_8);

	@Test
	public void testReadOneLine() throws Exception {
		setupReflog("logs/refs/heads/master", oneLine);

		ReflogReader reader = new ReflogReaderImpl(db, "refs/heads/master");
		ReflogEntry e = reader.getLastEntry();
		assertEquals(ObjectId
				.fromString("da85355dfc525c9f6f3927b876f379f46ccf826e"), e
				.getOldId());
		assertEquals(ObjectId
				.fromString("3e7549db262d1e836d9bf0af7e22355468f1717c"), e
				.getNewId());
		assertEquals("A O Thor Too", e.getWho().getName());
		assertEquals("authortoo@wri.tr", e.getWho().getEmailAddress());
		assertEquals(120, e.getWho().getTimeZoneOffset());
		assertEquals("2009-05-22T23:36:40", iso(e.getWho()));
		assertEquals("commit: Add a toString for debugging to RemoteRefUpdate",
				e.getComment());
	}

	private static String iso(PersonIdent id) {
		final SimpleDateFormat fmt;
		fmt = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
		fmt.setTimeZone(id.getTimeZone());
		return fmt.format(id.getWhen());
	}

	@Test
	public void testReadTwoLine() throws Exception {
		setupReflog("logs/refs/heads/master", twoLine);

		ReflogReader reader = new ReflogReaderImpl(db, "refs/heads/master");
		List<ReflogEntry> reverseEntries = reader.getReverseEntries();
		assertEquals(2, reverseEntries.size());
		ReflogEntry e = reverseEntries.get(0);
		assertEquals(ObjectId
				.fromString("c6734895958052a9dbc396cff4459dc1a25029ab"), e
				.getOldId());
		assertEquals(ObjectId
				.fromString("54794942a18a237c57a80719afed44bb78172b10"), e
				.getNewId());
		assertEquals("Same A U Thor", e.getWho().getName());
		assertEquals("same.author@example.com", e.getWho().getEmailAddress());
		assertEquals(60, e.getWho().getTimeZoneOffset());
		assertEquals("2009-05-22T22:36:42", iso(e.getWho()));
		assertEquals(
				"rebase finished: refs/heads/rr/renamebranch5 onto c6e3b9fe2da0293f11eae202ec35fb343191a82d",
				e.getComment());

		e = reverseEntries.get(1);
		assertEquals(ObjectId
				.fromString("0000000000000000000000000000000000000000"), e
				.getOldId());
		assertEquals(ObjectId
				.fromString("c6734895958052a9dbc396cff4459dc1a25029ab"), e
				.getNewId());
		assertEquals("A U Thor", e.getWho().getName());
		assertEquals("thor@committer.au", e.getWho().getEmailAddress());
		assertEquals(-60, e.getWho().getTimeZoneOffset());
		assertEquals("2009-05-22T20:36:41", iso(e.getWho()));
		assertEquals("branch: Created from rr/renamebranchv4", e.getComment());
	}

	@Test
	public void testReadWhileAppendIsInProgress() throws Exception {
		setupReflog("logs/refs/heads/master", twoLineWithAppendInProgress);
		ReflogReader reader = new ReflogReaderImpl(db, "refs/heads/master");
		List<ReflogEntry> reverseEntries = reader.getReverseEntries();
		assertEquals(2, reverseEntries.size());
		ReflogEntry e = reverseEntries.get(0);
		assertEquals(ObjectId
				.fromString("c6734895958052a9dbc396cff4459dc1a25029ab"), e
				.getOldId());
		assertEquals(ObjectId
				.fromString("54794942a18a237c57a80719afed44bb78172b10"), e
				.getNewId());
		assertEquals("Same A U Thor", e.getWho().getName());
		assertEquals("same.author@example.com", e.getWho().getEmailAddress());
		assertEquals(60, e.getWho().getTimeZoneOffset());
		assertEquals("2009-05-22T22:36:42", iso(e.getWho()));
		assertEquals(
				"rebase finished: refs/heads/rr/renamebranch5 onto c6e3b9fe2da0293f11eae202ec35fb343191a82d",
				e.getComment());
		// while similar to testReadTwoLine, we can assume that if we get the last entry
		// right, everything else is too
	}


	@Test
	public void testReadRightLog() throws Exception {
		setupReflog("logs/refs/heads/a", aLine);
		setupReflog("logs/refs/heads/master", masterLine);
		setupReflog("logs/HEAD", headLine);
		assertEquals("branch: change to master", db.getReflogReader("master")
				.getLastEntry().getComment());
		assertEquals("branch: change to a", db.getReflogReader("a")
				.getLastEntry().getComment());
		assertEquals("branch: change to HEAD", db.getReflogReader("HEAD")
				.getLastEntry().getComment());
	}

	@Test
	public void testReadLineWithMissingComment() throws Exception {
		setupReflog("logs/refs/heads/master", oneLineWithoutComment);
		final ReflogReader reader = db.getReflogReader("master");
		ReflogEntry e = reader.getLastEntry();
		assertEquals(ObjectId
				.fromString("da85355dfc525c9f6f3927b876f379f46ccf826e"), e
				.getOldId());
		assertEquals(ObjectId
				.fromString("3e7549db262d1e836d9bf0af7e22355468f1717c"), e
				.getNewId());
		assertEquals("A O Thor Too", e.getWho().getName());
		assertEquals("authortoo@wri.tr", e.getWho().getEmailAddress());
		assertEquals(120, e.getWho().getTimeZoneOffset());
		assertEquals("2009-05-22T23:36:40", iso(e.getWho()));
		assertEquals("",
				e.getComment());
	}

	@Test
	public void testNoLog() throws Exception {
		assertEquals(0, db.getReflogReader("master").getReverseEntries().size());
		assertNull(db.getReflogReader("master").getLastEntry());
	}

	@Test
	public void testCheckout() throws Exception {
		setupReflog("logs/HEAD", switchBranch);
		List<ReflogEntry> entries = db.getReflogReader(Constants.HEAD)
				.getReverseEntries();
		assertEquals(1, entries.size());
		ReflogEntry entry = entries.get(0);
		CheckoutEntry checkout = entry.parseCheckout();
		assertNotNull(checkout);
		assertEquals("master", checkout.getToBranch());
		assertEquals("new/work", checkout.getFromBranch());
	}

	@Test
	public void testSpecificEntryNumber() throws Exception {
		setupReflog("logs/refs/heads/master", twoLine);

		ReflogReader reader = new ReflogReaderImpl(db, "refs/heads/master");
		ReflogEntry e = reader.getReverseEntry(0);
		assertEquals(
				ObjectId.fromString("c6734895958052a9dbc396cff4459dc1a25029ab"),
				e.getOldId());
		assertEquals(
				ObjectId.fromString("54794942a18a237c57a80719afed44bb78172b10"),
				e.getNewId());
		assertEquals("Same A U Thor", e.getWho().getName());
		assertEquals("same.author@example.com", e.getWho().getEmailAddress());
		assertEquals(60, e.getWho().getTimeZoneOffset());
		assertEquals("2009-05-22T22:36:42", iso(e.getWho()));
		assertEquals(
				"rebase finished: refs/heads/rr/renamebranch5 onto c6e3b9fe2da0293f11eae202ec35fb343191a82d",
				e.getComment());

		e = reader.getReverseEntry(1);
		assertEquals(
				ObjectId.fromString("0000000000000000000000000000000000000000"),
				e.getOldId());
		assertEquals(
				ObjectId.fromString("c6734895958052a9dbc396cff4459dc1a25029ab"),
				e.getNewId());
		assertEquals("A U Thor", e.getWho().getName());
		assertEquals("thor@committer.au", e.getWho().getEmailAddress());
		assertEquals(-60, e.getWho().getTimeZoneOffset());
		assertEquals("2009-05-22T20:36:41", iso(e.getWho()));
		assertEquals("branch: Created from rr/renamebranchv4", e.getComment());

		assertNull(reader.getReverseEntry(3));
	}

	private void setupReflog(String logName, byte[] data)
			throws FileNotFoundException, IOException {
		File logfile = new File(db.getCommonDirectory(), logName);
		if (!logfile.getParentFile().mkdirs()
				&& !logfile.getParentFile().isDirectory()) {
			throw new IOException(
					"oops, cannot create the directory for the test reflog file"
							+ logfile);
		}
		try (FileOutputStream fileOutputStream = new FileOutputStream(logfile)) {
			fileOutputStream.write(data);
		}
	}

}
