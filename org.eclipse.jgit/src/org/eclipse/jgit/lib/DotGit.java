package org.eclipse.jgit.lib;

import java.io.File;
import java.io.IOException;
import java.text.MessageFormat;

import org.eclipse.jgit.internal.JGitText;
import org.eclipse.jgit.util.FS;
import org.eclipse.jgit.util.IO;
import org.eclipse.jgit.util.RawParseUtils;
import org.eclipse.jgit.util.SystemReader;

/**
 * Internal utility for working with ".git" content.
 */
class DotGit {

	static File resolveCommonDir(File dir) throws IOException {
		File commonDir = loadPathFile(dir, "commondir"); //$NON-NLS-1$
		return commonDir != null ? commonDir : dir;
	}

	static File loadPathFile(File dir, String name) throws IOException {
		File pathFile = new File(dir, name);
		if (pathFile.isFile()) {
			String content = new String(IO.readFully(pathFile)).trim();
			File result = new File(content);
			return !result.isAbsolute()
					? new File(dir, content).getCanonicalFile()
					: result;
		}
		return null;
	}

	static File getSymRef(File workTree, File dotGit, FS fs)
			throws IOException {
		byte[] content = IO.readFully(dotGit);
		if (!isSymRef(content)) {
			throw new IOException(MessageFormat.format(
					JGitText.get().invalidGitdirRef, dotGit.getAbsolutePath()));
		}

		int pathStart = 8;
		int lineEnd = RawParseUtils.nextLF(content, pathStart);
		while (content[lineEnd - 1] == '\n' || (content[lineEnd - 1] == '\r'
				&& SystemReader.getInstance().isWindows())) {
			lineEnd--;
		}
		if (lineEnd == pathStart) {
			throw new IOException(MessageFormat.format(
					JGitText.get().invalidGitdirRef, dotGit.getAbsolutePath()));
		}

		String gitdirPath = RawParseUtils.decode(content, pathStart, lineEnd);
		File gitdirFile = fs.resolve(workTree, gitdirPath);
		if (gitdirFile.isAbsolute()) {
			return gitdirFile;
		}
		return new File(workTree, gitdirPath).getCanonicalFile();
	}

	private static boolean isSymRef(byte[] ref) {
		if (ref.length < 9)
			return false;
		return /**/ref[0] == 'g' //
				&& ref[1] == 'i' //
				&& ref[2] == 't' //
				&& ref[3] == 'd' //
				&& ref[4] == 'i' //
				&& ref[5] == 'r' //
				&& ref[6] == ':' //
				&& ref[7] == ' ';
	}

}
