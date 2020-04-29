/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2000-2012, 2014, 2016, 2017, 2018, 2019  AO Industries, Inc.
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
 * along with aoserv-daemon.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aoindustries.aoserv.daemon.email;

import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.email.MajordomoServer;
import com.aoindustries.aoserv.client.linux.PosixPath;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.io.FileUtils;
import com.aoindustries.io.unix.UnixFile;
import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;

/**
 * @author  AO Industries, Inc.
 */
final public class EmailListManager {

	private static final Charset ENCODING = StandardCharsets.UTF_8;

	private EmailListManager() {
	}

	/**
	 * Reads the address list from the file system.
	 */
	public static String getEmailListFile(PosixPath path) throws IOException, SQLException {
		Server thisServer = AOServDaemon.getThisServer();
		OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		if(
			osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
			&& osvId != OperatingSystemVersion.CENTOS_7_X86_64
		) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

		return FileUtils.readFileAsString(new File(path.toString()), ENCODING);
	}

	/**
	 * Constructs a <code>List</code> providing all information.  The
	 * new <code>List</code> is stored in the database.
	 */
	public static void removeEmailListAddresses(PosixPath path) throws IOException, SQLException {
		OperatingSystemVersion osv = AOServDaemon.getThisServer().getHost().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		if(
			osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
			&& osvId != OperatingSystemVersion.CENTOS_7_X86_64
		) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

		File file = new File(path.toString());
		if(file.exists()) FileUtils.delete(file);
		// TODO: Clean-up directories, up to and possibly including /etc/mail/lists itself
		// TODO: Background clean-up of orphaned lists
	}

	/**
	 * Writes the address list to the file system.
	 */
	public synchronized static void setEmailListFile(
		PosixPath path,
		String file,
		int uid,
		int gid,
		int mode
	) throws IOException, SQLException {
		Server thisServer = AOServDaemon.getThisServer();
		OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		if(
			osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
			&& osvId != OperatingSystemVersion.CENTOS_7_X86_64
		) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

		int uid_min = thisServer.getUidMin().getId();
		int gid_min = thisServer.getGidMin().getId();

		// Remove any '/r'
		StringBuilder SB=new StringBuilder();
		int len=file.length();
		for(int c=0;c<len;c++) {
			char ch=file.charAt(c);
			if(ch!='\r') SB.append(ch);
		}
		// Make sure ends with '\n'
		if(SB.length()>0 && SB.charAt(SB.length()-1)!='\n') SB.append('\n');

		// If a majordomo list, add any new directories
		if(path.toString().startsWith(MajordomoServer.MAJORDOMO_SERVER_DIRECTORY.toString() + '/')) {
			// TODO: Create /etc/mail/majordomo when first needed
			UnixFile pathUF=new UnixFile(path.toString());
			UnixFile listDir=pathUF.getParent();
			if(!listDir.getStat().exists()) {
				UnixFile serverDir=listDir.getParent();
				if(!serverDir.getStat().exists()) {
					serverDir.mkdir().setMode(0750);
				}
				listDir.mkdir().setMode(0750);
			}
		} else {
			// TODO: Create /etc/mail/lists when first needed
		}

		// TODO: Atomic write and restorecon
		UnixFile tempUF = UnixFile.mktemp(path+".new.");
		try (
			Writer out = new OutputStreamWriter(
				tempUF.getSecureOutputStream(uid, gid, mode, true, uid_min, gid_min),
				ENCODING
			)
		) {
			out.write(SB.toString());
		}

		// Move the new file into place
		tempUF.renameTo(new UnixFile(path.toString()));
	}
}