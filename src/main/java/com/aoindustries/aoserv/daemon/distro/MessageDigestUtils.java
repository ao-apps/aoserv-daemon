/*
 * Copyright 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.distro;

import com.aoindustries.md5.MD5;
import com.aoindustries.util.BufferManager;
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
