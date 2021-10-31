/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2017, 2021  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of aoserv-daemon.
 *
 * aoserv-daemon is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * aoserv-daemon is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with aoserv-daemon.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.aoindustries.aoserv.daemon.distro;

import com.aoapps.hodgepodge.md5.MD5;
import com.aoapps.lang.util.BufferManager;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Creates the server distribution database contents.
 *
 * @author  AO Industries, Inc.
 */
class MessageDigestUtils {

	static byte[] hashInput(MessageDigest digest, InputStream in) throws IOException {
		digest.reset();
		byte[] buff = BufferManager.getBytes();
		try {
			int numBytes;
			while((numBytes = in.read(buff)) != -1) {
				digest.update(buff, 0, numBytes);
			}
		} finally {
			BufferManager.release(buff, false);
		}
		return digest.digest();
	}

	static MessageDigest getSha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch(NoSuchAlgorithmException e) {
			throw new AssertionError("SHA-256 should exist on all Java runtimes", e);
		}
	}

	static String getHexChars(long sha256_0, long sha256_1, long sha256_2, long sha256_3) {
		return MD5.getMD5String(sha256_0, sha256_1) + MD5.getMD5String(sha256_2, sha256_3);
	}

	private MessageDigestUtils() {
		// Make no instances
	}
}
