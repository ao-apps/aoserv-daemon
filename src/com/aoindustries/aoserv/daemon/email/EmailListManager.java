/*
 * Copyright 2000-2012, 2014, 2016, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.email;

import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.MajordomoServer;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.io.FileUtils;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.sql.SQLException;

/**
 * @author  AO Industries, Inc.
 */
final public class EmailListManager {

	private EmailListManager() {
	}

	/**
	 * Reads the address list from the file system.
	 */
	public static String getEmailListFile(String path) throws IOException, SQLException {
		AOServer thisAoServer = AOServDaemon.getThisAOServer();
		OperatingSystemVersion osv = thisAoServer.getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		if(
			osvId != OperatingSystemVersion.MANDRIVA_2006_0_I586
			&& osvId != OperatingSystemVersion.REDHAT_ES_4_X86_64
			&& osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
		) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

		int uid_min = thisAoServer.getUidMin().getID();
		int gid_min = thisAoServer.getGidMin().getID();

		UnixFile file = new UnixFile(path);
		Stat fileStat = file.getStat();
		StringBuilder sb=new StringBuilder((int) fileStat.getSize());
		InputStream fin=new BufferedInputStream(file.getSecureInputStream(uid_min, gid_min));
		int ch;
		while ((ch = fin.read()) != -1) sb.append((char) ch);
		return sb.toString();
	}

	/**
	 * Constructs a <code>EmailList</code> providing all information.  The
	 * new <code>EmailList</code> is stored in the database.
	 */
	public static void removeEmailListAddresses(String path) throws IOException, SQLException {
		OperatingSystemVersion osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		if(
			osvId != OperatingSystemVersion.MANDRIVA_2006_0_I586
			&& osvId != OperatingSystemVersion.REDHAT_ES_4_X86_64
			&& osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
		) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

		File file = new File(path);
		if(file.exists()) FileUtils.delete(file);
	}

	/**
	 * Writes the address list to the file system.
	 */
	public synchronized static void setEmailListFile(
		String path,
		String file,
		int uid,
		int gid,
		int mode
	) throws IOException, SQLException {
		AOServer thisAoServer = AOServDaemon.getThisAOServer();
		OperatingSystemVersion osv = thisAoServer.getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		if(
			osvId != OperatingSystemVersion.MANDRIVA_2006_0_I586
			&& osvId != OperatingSystemVersion.REDHAT_ES_4_X86_64
			&& osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
		) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

		int uid_min = thisAoServer.getUidMin().getID();
		int gid_min = thisAoServer.getGidMin().getID();

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
		boolean isMajordomo=path.startsWith(MajordomoServer.MAJORDOMO_SERVER_DIRECTORY);
		if(isMajordomo) {
			UnixFile pathUF=new UnixFile(path);
			UnixFile listDir=pathUF.getParent();
			if(!listDir.getStat().exists()) {
				UnixFile serverDir=listDir.getParent();
				if(!serverDir.getStat().exists()) {
					serverDir.mkdir().setMode(0750);
				}
				listDir.mkdir().setMode(0750);
			}
		}

		UnixFile tempUF=UnixFile.mktemp(path+".new.", false);
		PrintWriter out=new PrintWriter(
			new BufferedOutputStream(
				tempUF.getSecureOutputStream(uid, gid, mode, true, uid_min, gid_min)
			)
		);
		try {
			out.print(SB.toString());
		} finally {
			out.flush();
			out.close();
		}

		// Move the new file into place
		tempUF.renameTo(new UnixFile(path));
	}
}
