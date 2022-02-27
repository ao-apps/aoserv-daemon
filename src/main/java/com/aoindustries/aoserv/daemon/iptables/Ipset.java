/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2012, 2013, 2017, 2018, 2020, 2021, 2022  AO Industries, Inc.
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
package com.aoindustries.aoserv.daemon.iptables;

import com.aoapps.collections.AoCollections;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.lang.ProcessResult;
import com.aoindustries.aoserv.client.net.IpAddress;
import java.io.BufferedReader;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles access to ipset.
 *
 * @author  AO Industries, Inc.
 */
public final class Ipset {

	/** Make no instances. */
	private Ipset() {throw new AssertionError();}

	private static final Logger logger = Logger.getLogger(Ipset.class.getName());

	public static final int MAX_IPSET_SIZE = 65535;

	public static final short HOST_NETWORK_PREFIX = 32;

	private static final String IPSET = "/sbin/ipset";

	private static final Charset CHARSET = Charset.forName("UTF-8");

	private static final String SAVE_COMMENT = "# Automatically generated by " + Ipset.class.getName() + "\n";

	/**
	 * ipset has a global namespace.  To avoid name collisions with different
	 * subsystems that use ipset, each subsystem has its own single character
	 * prefix.
	 */
	public enum NamespacePrefix {
		/**
		 * Used by each IpSet.
		 */
		I,

		/**
		 * Used by each Set.
		 *
		 * @see  Set
		 */
		R
	}

	/**
	 * To minimize the kernel locking duration, each ipset is only updated with
	 * what has changed since the last build.
	 */
	//private static final Map<Set, Set<Integer>> lastNetworks = new HashMap<>();

	/**
	 * Calls ipset -S, skipping any comment lines
	 *
	 * @param  setName        the name of the set
	 * @param  missingAsNull  when true, a missing set will be returned as null, otherwise will throw an exception
	 */
	public static String save(String setName, boolean missingAsNull) throws IOException {
		ProcessResult result = ProcessResult.exec(
			new String[] {
				IPSET,
				"-S",
				setName
			},
			CHARSET
		);
		if(result.getExitVal()==0) {
			// Skip any comments
			try (BufferedReader in = new BufferedReader(new StringReader(result.getStdout()))) {
				StringWriter out = new StringWriter();
				try {
					String line;
					while((line=in.readLine())!=null) {
						line = line.trim();
						if(!line.startsWith("#")) {
							out.write(line);
							out.write('\n');
						}
					}
				} finally {
					out.close();
				}
				return out.toString();
			}
		} else {
			String stderr = result.getStderr().trim();
			if(!missingAsNull || !stderr.endsWith("Unknown set")) throw new IOException("Non-zero exit value from " + IPSET + " -S: exitVal=" + result.getExitVal()+", stderr=" + stderr);
			return null;
		}
	}

	public static enum SetType {
		iphash,
		iptree,
		iptreemap
	}

	/**
	 * Calls ipset -N
	 */
	public static void create(String setName, SetType setType, String... options) throws IOException {
		String[] newCommand = new String[4 + options.length];
		newCommand[0] = IPSET;
		newCommand[1] = "-N";
		newCommand[2] = setName;
		newCommand[3] = setType.name();
		System.arraycopy(options, 0, newCommand, 4, options.length);
		ProcessResult result = ProcessResult.exec(newCommand, CHARSET);
		if(result.getExitVal()!=0) throw new IOException("Non-zero exit value from " + IPSET + " -N: exitVal=" + result.getExitVal()+", stderr=" + result.getStderr().trim());
	}

	/**
	 * Calls ipset -D
	 */
	public static void delete(String setName, int entry) throws IOException {
		ProcessResult result = ProcessResult.exec(
			new String[] {
				IPSET,
				"-D",
				setName,
				IpAddress.getIPAddressForInt(entry)
			},
			CHARSET
		);
		if(result.getExitVal()!=0) throw new IOException("Non-zero exit value from " + IPSET + " -D: exitVal=" + result.getExitVal()+", stderr=" + result.getStderr().trim());
	}

	/**
	 * Calls ipset -A
	 */
	public static void add(String setName, int entry) throws IOException {
		ProcessResult result = ProcessResult.exec(
			new String[] {
				IPSET,
				"-A",
				setName,
				IpAddress.getIPAddressForInt(entry)
			},
			CHARSET
		);
		if(result.getExitVal()!=0) throw new IOException("Non-zero exit value from " + IPSET + " -A: exitVal=" + result.getExitVal()+", stderr=" + result.getStderr().trim());
	}

	/**
	 * Parses an ipset save file, returning the mutable set of IP addresses in order dumped.
	 */
	public static void parse(String save, Set<Integer> entries) throws IOException {
		try (BufferedReader in = new BufferedReader(new StringReader(save))) {
			String line;
			while((line=in.readLine())!=null) {
				line = line.trim();
				if(line.startsWith("-A ")) {
					int spacePos = line.indexOf(' ', 3);
					if(spacePos==-1) throw new IOException("Unable to find second space");
					entries.add(IpAddress. getIntForIPAddress(line.substring(spacePos+1)));
				}
			}
		}
	}

	/**
	 * Synchronizes a single set to the expected entries, both in-kernel and on-disk versions.
	 * Creates set in kernel if missing.
	 * Adds/removes any necessary changes.
	 * Updates on-disk only if missing or set modified.
	 *
	 * @param  entries        the ip or network entries, only the first <code>MAX_IPSET_SIZE</code> entries will be used
	 * @param  networkPrefix  the network prefix or <code>HOST_NETWORK_PREFIX</code> for individual hosts
	 * @param  setName        the set name used both in-kernel and on-disk
	 * @param  setDir         the directory that stores the on-disk version
	 */
	public static void synchronize(
		Set<Integer> entries,
		short networkPrefix,
		String setName,
		PosixFile setDir
	) throws IOException {
		Set<Integer> unusedEntries;
		if(entries.size()>MAX_IPSET_SIZE) {
			logger.log(
				Level.WARNING,
				"Only the first {0} entries used for ipset \"{1}\"",
				new Object[] {
					MAX_IPSET_SIZE,
					setName
				}
			);
			unusedEntries = AoCollections.newHashSet(entries.size() - MAX_IPSET_SIZE);
			int count = 0;
			for(Integer entry : entries) {
				if(count>=MAX_IPSET_SIZE) unusedEntries.add(entry);
				else count++;
			}
		} else {
			unusedEntries = Collections.emptySet();
		}

		// TODO: Cache values between passes for efficiency

		// Dump current set from kernel
		String save = save(setName, true);
		if(save==null) {
			// Create new set
			if(networkPrefix==HOST_NETWORK_PREFIX) {
				create(setName, SetType.iphash);
			} else {
				create(setName, SetType.iphash, "--netmask", Short.toString(networkPrefix));
			}

			// Dump new set
			save = save(setName, false);
		}

		// Will be set to true when re-dump is required
		boolean modified = false;

		// Parse current set, deleting any that should no longer exist, flagging as modified
		Set<Integer> existingEntries = AoCollections.newLinkedHashSet(
			// Leave room for 25% growth before any rehash
			(entries.size() * 5) >> 2
		);
		parse(save, existingEntries);
		Iterator<Integer> iter = existingEntries.iterator();
		while(iter.hasNext()) {
			Integer existing = iter.next();
			if(!entries.contains(existing) || unusedEntries.contains(existing)) {
				delete(setName, existing);
				iter.remove();
				modified = true;
			}
		}

		// Add any missing, flagging as modified
		for(Integer entry : entries) {
			if(!unusedEntries.contains(entry) && !existingEntries.contains(entry)) {
				add(setName, entry);
				modified = true;
			}
		}

		// Re-list if modified to get on-disk format
		if(modified) save = save(setName, false);

		// Add-in comment line about automatically generated
		save = SAVE_COMMENT + save;

		// Update on-disk storage if missing or changed
		PosixFile setFile    = new PosixFile(setDir, setName+".ipset",     true);
		byte[] contents = save.getBytes(CHARSET.name());
		if(
			!setFile.getStat().exists()
			|| !setFile.contentEquals(contents)
		) {
			// Create in new file
			PosixFile newSetFile = new PosixFile(setDir, setName+".ipset.new", true);
			try (OutputStream out = new FileOutputStream(newSetFile.getFile())) {
				out.write(contents);
			} finally {
				newSetFile.setMode(0600);
			}
			// Move over old file
			newSetFile.renameTo(setFile);
		}
	}
}
