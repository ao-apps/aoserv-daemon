/*
 * Copyright 2000-2013, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.unix.linux;

import com.aoindustries.aoserv.daemon.unix.UnixProcess;
import com.aoindustries.io.unix.UnixFile;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A <code>UnixProcess</code> represents a process
 * running on any Unix machine.
 *
 * @author  AO Industries, Inc.
 */
public class LinuxProcess extends UnixProcess {

	private static final File proc = new File("/proc");

	/** The <code>/proc/<i>pid</i></code> file is cached once created */
	private File processProc;

	/**
	 * Constructs a Linux process given its process ID.
	 */
	public LinuxProcess(int pid) {
		super(pid);
	}

	/**
	 * Determines the group ID of the currently running process.
	 * The GID is considered the group owner of the file in the
	 * /proc directory.  If the process is not running, a
	 * FileNotFoundException is thrown.
	 */
	@Override
	public int getGid() throws IOException {
		return new UnixFile(getProc().getPath()).getStat().getGid();
	}

	/**
	 * Gets the directory that contains the proc info.
	 *
	 * @return  the <code>File</code>
	 * @exception  IOException if the proc is not mounted
	 */
	private File getProc() throws IOException {
		synchronized(this) {
			if(processProc==null) {
				processProc=new File(proc, String.valueOf(pid));
			}
			return processProc;
		}
	}

	/**
	 * Determines the user ID of the currently running process.
	 * The UID is considered the owner of the file in the
	 * /proc directory.  If the process is not running, a
	 * FileNotFoundException is thrown.
	 */
	@Override
	public int getUid() throws IOException {
		return new UnixFile(getProc().getPath()).getStat().getUid();
	}

	/**
	 * Determines if the process is currently running.  The process
	 * is considered running if a directory exists in /proc.
	 */
	@Override
	public boolean isRunning() throws IOException {
		return getProc().exists();
	}

	/**
	 * Gets the command line from <code>/proc/<i>pid</i>/cmdline</code>, split
	 * on null bytes.
	 */
	public String[] getCmdline() throws IOException {
		List<String> split = new ArrayList<>();
		StringBuilder sb = new StringBuilder();
		File cmdlineFile = new File(proc, "cmdline");
		Reader in = new FileReader(cmdlineFile);
		try {
			int ch;
			while((ch = in.read()) != -1) {
				if(ch == 0) {
					split.add(sb.toString());
					sb.setLength(0);
				} else {
					sb.append((char)ch);
				}
			}
		} finally {
			in.close();
		}
		if(sb.length() != 0) throw new IOException("cmdline not null terminated from " + cmdlineFile);
		return split.toArray(new String[split.size()]);
	}

	/**
	 * Gets the status from <code>/proc/<i>pid</i>/status</code>.
	 * The colon (:) is removed from the field names.
	 *
	 * @see  #getStatus(java.lang.String)
	 */
	public Map<String,String> getStatus() throws IOException {
		File statusFile = new File(proc, "status");
		final int expectedMaxLength = 45; // "wc -l /proc/*/status" shows maximum 45 lines in kernel 3.10.0-514.16.1.el7.x86_64
		Map<String,String> status = new LinkedHashMap<>(expectedMaxLength *4/3+1);
		BufferedReader in = new BufferedReader(new FileReader(statusFile));
		try {
			String line;
			while((line = in.readLine()) != null) {
				// Have seen empty lines, skip them
				if(!line.isEmpty()) {
					int tabPos = line.indexOf('\t');
					if(tabPos == -1) throw new IOException("No tab found in line from " + statusFile + ": " + line);
					if(tabPos < 1) throw new IOException("Empty name column from " + statusFile + ": " + line);
					if(line.charAt(tabPos - 1) != ':') throw new IOException("Not colon before tab from " + statusFile + ": " + line);
					String name = line.substring(0, tabPos - 1);
					String value = line.substring(tabPos + 1);
					if(status.put(name, value) != null) throw new IOException("Duplicate name from " + statusFile + ": " + name);
				}
			}
		} finally {
			in.close();
		}
		return status;
	}

	/**
	 * Gets one field of the status from <code>/proc/<i>pid</i>/status</code>.
	 * The colon (:) is removed from the field names.
	 *
	 * @return  the corresponding value or {@code null} if not found
	 *
	 * @see  #getStatus()
	 */
	public String getStatus(String name) throws IOException {
		File statusFile = new File(proc, "status");
		BufferedReader in = new BufferedReader(new FileReader(statusFile));
		try {
			String line;
			while((line = in.readLine()) != null) {
				// Have seen empty lines, skip them
				if(!line.isEmpty()) {
					int tabPos = line.indexOf('\t');
					if(tabPos == -1) throw new IOException("No tab found in line from " + statusFile + ": " + line);
					if(tabPos < 1) throw new IOException("Empty name column from " + statusFile + ": " + line);
					if(line.charAt(tabPos - 1) != ':') throw new IOException("Not colon before tab from " + statusFile + ": " + line);
					if(name.equals(line.substring(0, tabPos - 1))) {
						return line.substring(tabPos + 1);
					}
				}
			}
		} finally {
			in.close();
		}
		return null;
	}
}
