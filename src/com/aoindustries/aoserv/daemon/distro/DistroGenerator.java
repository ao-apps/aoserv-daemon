/*
 * Copyright 2001-2013, 2014, 2015, 2016, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.distro;

import com.aoindustries.aoserv.client.Architecture;
import com.aoindustries.aoserv.client.DistroFileType;
import com.aoindustries.aoserv.client.OperatingSystem;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.lang.SysExits;
import com.aoindustries.md5.MD5;
import com.aoindustries.md5.MD5Utils;
import com.aoindustries.sql.SQLUtility;
import com.aoindustries.util.AoArrays;
import com.aoindustries.util.ErrorPrinter;
import com.aoindustries.util.StringUtility;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EmptyStackException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

/**
 * Creates the server distribution database contents.
 *
 * @author  AO Industries, Inc.
 */
final public class DistroGenerator {

	private static final int ROWS_PER_TRANSACTION = 1000;

	private static final String DEFAULT_ROOT = "/distro";

	private static Set<String> readFileList(String name) throws IOException {
		Set<String> filenames = new HashSet<>();
		InputStream resourceIn = DistroGenerator.class.getResourceAsStream(name);
		if(resourceIn == null) throw new IOException("Resource not found: " + name);
		try {
			try (BufferedReader in = new BufferedReader(new InputStreamReader(resourceIn))) {
				String filename;
				while((filename = in.readLine()) != null) {
					String trimmed = filename.trim();
					if(trimmed.length() > 0 && trimmed.charAt(0) != '#') {
						if(!filenames.add(filename)) {
							throw new AssertionError("Duplicate filename in " + name + ": " + filename);
						}
					}
				}
			}
		} finally {
			resourceIn.close();
		}
		return filenames;
	}

	private static final Map<Integer,Set<String>>
		configs = new HashMap<>(),
		nevers = new HashMap<>(),
		noRecurses = new HashMap<>(),
		optionals = new HashMap<>(),
		prelinks = new HashMap<>(),
		users = new HashMap<>()
	;

	/*
	 * Load the unchanging resources on class initialization
	*/
	static {
		try {
			// configs.txt
			configs.put(OperatingSystemVersion.CENTOS_5_I686_AND_X86_64, readFileList("configs-centos-5.txt"));
			configs.put(OperatingSystemVersion.CENTOS_5_DOM0_X86_64,     readFileList("configs-centos-5.dom0.txt"));
			configs.put(OperatingSystemVersion.CENTOS_7_X86_64,          readFileList("configs-centos-7.txt"));
			configs.put(OperatingSystemVersion.CENTOS_7_DOM0_X86_64,     readFileList("configs-centos-7.dom0.txt"));
			configs.put(OperatingSystemVersion.REDHAT_ES_4_X86_64,       readFileList("configs-redhat-es_4.txt"));
			// nevers.txt
			nevers.put(OperatingSystemVersion.CENTOS_5_I686_AND_X86_64, readFileList("nevers-centos-5.txt"));
			nevers.put(OperatingSystemVersion.CENTOS_5_DOM0_X86_64,     readFileList("nevers-centos-5.dom0.txt"));
			nevers.put(OperatingSystemVersion.CENTOS_7_X86_64,          readFileList("nevers-centos-7.txt"));
			nevers.put(OperatingSystemVersion.CENTOS_7_DOM0_X86_64,     readFileList("nevers-centos-7.dom0.txt"));
			nevers.put(OperatingSystemVersion.REDHAT_ES_4_X86_64,       readFileList("nevers-redhat-es_4.txt"));
			// no_recurses.txt
			noRecurses.put(OperatingSystemVersion.CENTOS_5_I686_AND_X86_64, readFileList("no_recurses-centos-5.txt"));
			noRecurses.put(OperatingSystemVersion.CENTOS_5_DOM0_X86_64,     readFileList("no_recurses-centos-5.dom0.txt"));
			noRecurses.put(OperatingSystemVersion.CENTOS_7_X86_64,          readFileList("no_recurses-centos-7.txt"));
			noRecurses.put(OperatingSystemVersion.CENTOS_7_DOM0_X86_64,     readFileList("no_recurses-centos-7.dom0.txt"));
			noRecurses.put(OperatingSystemVersion.REDHAT_ES_4_X86_64,       readFileList("no_recurses-redhat-es_4.txt"));
			// optionals.txt
			optionals.put(OperatingSystemVersion.CENTOS_5_I686_AND_X86_64, readFileList("optionals-centos-5.txt"));
			optionals.put(OperatingSystemVersion.CENTOS_5_DOM0_X86_64,     readFileList("optionals-centos-5.dom0.txt"));
			optionals.put(OperatingSystemVersion.CENTOS_7_X86_64,          readFileList("optionals-centos-7.txt"));
			optionals.put(OperatingSystemVersion.CENTOS_7_DOM0_X86_64,     readFileList("optionals-centos-7.dom0.txt"));
			optionals.put(OperatingSystemVersion.REDHAT_ES_4_X86_64,       readFileList("optionals-redhat-es_4.txt"));
			// prelinks.txt
			optionals.put(OperatingSystemVersion.CENTOS_5_I686_AND_X86_64, readFileList("prelinks-centos-5.txt"));
			optionals.put(OperatingSystemVersion.CENTOS_5_DOM0_X86_64,     Collections.emptySet());
			optionals.put(OperatingSystemVersion.CENTOS_7_X86_64,          Collections.emptySet());
			optionals.put(OperatingSystemVersion.CENTOS_7_DOM0_X86_64,     Collections.emptySet());
			optionals.put(OperatingSystemVersion.REDHAT_ES_4_X86_64,       Collections.emptySet());
			// users.txt
			users.put(OperatingSystemVersion.CENTOS_5_I686_AND_X86_64, readFileList("users-centos-5.txt"));
			users.put(OperatingSystemVersion.CENTOS_5_DOM0_X86_64,     readFileList("users-centos-5.dom0.txt"));
			users.put(OperatingSystemVersion.CENTOS_7_X86_64,          readFileList("users-centos-7.txt"));
			users.put(OperatingSystemVersion.CENTOS_7_DOM0_X86_64,     readFileList("users-centos-7.dom0.txt"));
			users.put(OperatingSystemVersion.REDHAT_ES_4_X86_64,       readFileList("users-redhat-es_4.txt"));
		} catch(IOException e) {
			throw new ExceptionInInitializerError(e);
		}
	}

	public static void main(String[] args) {
		try {
			DistroGenerator generator;
			if(args.length > 0) {
				generator = new DistroGenerator(args[0]);
			} else {
				generator = new DistroGenerator();
			}
			PrintWriter out = new PrintWriter(System.out);
			PrintWriter err = new PrintWriter(System.err);
			try {
				generator.run(out, err);
			} finally {
				out.flush();
				err.flush();
			}
		} catch(IOException e) {
			ErrorPrinter.printStackTraces(e);
			System.exit(SysExits.EX_IOERR);
		} catch(FoundNeversException e) {
			System.err.println("Files exist but are listed in nevers:");
			for(String filename : e.getFoundNevers()) {
				System.err.println(filename);
			}
			System.err.flush();
			System.exit(SysExits.EX_SOFTWARE);
		} catch(InterruptedException | RuntimeException e) {
			ErrorPrinter.printStackTraces(e);
			System.exit(SysExits.EX_SOFTWARE);
		}
	}

	private final String root;

	private final int numThreads;

	public DistroGenerator(
		String root,
		int numThreads
	) {
		this.root = root;
		this.numThreads = numThreads;
	}

	public DistroGenerator(String root) {
		this(
			root,
			Math.min(4, Runtime.getRuntime().availableProcessors() * 2)
		);
	}

	public DistroGenerator() {
		this(DEFAULT_ROOT);
	}

	private final Map<Integer,Map<Integer,String>> usernames = new HashMap<>();
	private String getUsername(OSFilename osFilename, int fileUID) throws IOException {
		Integer I = osFilename.operating_system_version;
		synchronized(usernames) {
			Map<Integer,String> realNames = usernames.get(I);
			if(realNames == null) {
				realNames = new HashMap<>();
				try (BufferedReader in = new BufferedReader(
					new InputStreamReader(
						new BufferedInputStream(
							new FileInputStream(
								new UnixFile(
									root + '/' + osFilename.getOSName() + '/' + osFilename.getOSVersion() + '/' + osFilename.getOSArchitecture() + "/etc/passwd"
								).getFile()
							)
						)
					)
				)) {
					String line;
					while ((line = in.readLine()) != null) {
						List<String> fields = StringUtility.splitString(line, ':');
						String username = fields.get(0);
						Integer userID = Integer.parseInt(fields.get(2));
						if(!realNames.containsKey(userID)) realNames.put(userID, username);
					}
				}
				usernames.put(I, realNames);
			}
			String username = realNames.get(Integer.valueOf(fileUID));
			if(username == null) throw new IOException("Unable to find username: " + fileUID + " for file " + osFilename);
			return username;
		}
	}

	private final Map<Integer,Map<Integer,String>> groupnames = new HashMap<>();
	private String getGroupname(OSFilename osFilename, int fileGID) throws IOException {
		Integer I = osFilename.operating_system_version;
		synchronized(groupnames) {
			Map<Integer,String> realGroups = groupnames.get(I);
			if(realGroups == null) {
				realGroups = new HashMap<>();
				try (BufferedReader in = new BufferedReader(
					new InputStreamReader(
						new BufferedInputStream(
							new FileInputStream(
								new UnixFile(
									root + '/' + osFilename.getOSName() + '/' + osFilename.getOSVersion() + '/' + osFilename.getOSArchitecture() + "/etc/group"
								).getFile()
							)
						)
					)
				)) {
					String line;
					while ((line = in.readLine()) != null) {
						List<String> fields = StringUtility.splitString(line, ':');
						String groupname = fields.get(0);
						Integer groupID = Integer.parseInt(fields.get(2));
						if(!realGroups.containsKey(groupID)) realGroups.put(groupID, groupname);
					}
				}
				groupnames.put(I, realGroups);
			}
			String groupname = realGroups.get(Integer.valueOf(fileGID));
			if(groupname == null) throw new IOException("Unable to find group name " + fileGID + " for file " + osFilename);
			return groupname;
		}
	}

	private class RunState {

		private Map<Integer,Set<String>> copy(Map<Integer,Set<String>> map) {
			Map<Integer,Set<String>> newMap = new HashMap<>();
			for(Map.Entry<Integer,Set<String>> entry : map.entrySet()) {
				newMap.put(
					entry.getKey(),
					new HashSet<>(entry.getValue())
				);
			}
			return newMap;
		}

		/**
		 * Track which filenames have not been seen.
		 */
		private final Map<Integer,Set<String>>
			unseenConfigs = copy(configs),
			unseenNevers = copy(nevers),
			unseenNoRecurses = copy(noRecurses),
			unseenOptionals = copy(optionals),
			unseenPrelinks = copy(prelinks),
			unseenUsers = copy(users)
		;

		private final PrintWriter out;
		private final PrintWriter err;

		private final DistroGeneratorThread[] generators;

		private final Object nextFilenameLock = new Object();

		private Stack<String> currentDirectories;
		private Stack<String[]> currentLists;
		private Stack<Integer> currentIndexes;

		private boolean done = false;

		private RunState(
			PrintWriter out,
			PrintWriter err
		) {
			this.out = out;
			this.err = err;
			this.generators = new DistroGeneratorThread[numThreads];
		}

		private String getNextFilename(OSFilename temp) throws IOException {
			synchronized(nextFilenameLock) {
				String filename;

				if(done) {
					filename = null;
				} else {
					// Initialize the stacks, if needed
					if(currentDirectories == null) {
						(currentDirectories = new Stack<>()).push("");
						(currentLists=new Stack<>()).push(new String[] {""});
						(currentIndexes=new Stack<>()).push(0);
					}
				}
				String currentDirectory = null;
				String[] currentList = null;
				int currentIndex = -1;
				try {
					currentDirectory = currentDirectories.peek();
					currentList = currentLists.peek();
					currentIndex = currentIndexes.peek();

					// Undo the stack as far as needed
					while(currentDirectory != null && currentIndex >= currentList.length) {
						currentDirectories.pop();
						currentDirectory = currentDirectories.peek();
						currentLists.pop();
						currentList = currentLists.peek();
						currentIndexes.pop();
						currentIndex = currentIndexes.peek();
					}
				} catch(EmptyStackException err) {
					currentDirectory = null;
				}
				if(currentDirectory == null) {
					filename = null;
					done = true;
				} else {
					// Get the current filename
					if(currentDirectory.equals("/")) filename = "/" + currentList[currentIndex++];
					else filename = currentDirectory + '/' + currentList[currentIndex++];

					// Set to the next file
					currentIndexes.pop();
					currentIndexes.push(currentIndex);

					// Recurse for directories
					try {
						UnixFile unixFile = new UnixFile(root + filename);
						long statMode = unixFile.getStat().getRawMode();
						temp.parseValues(filename);
						if(
							!UnixFile.isSymLink(statMode)
							&& UnixFile.isDirectory(statMode)
							&& !isUser(temp)
							&& !isNoRecurse(temp)
						) {
							// Push on stacks for next level
							currentDirectories.push(filename);
							String[] list = unixFile.list();
							if(list == null) list = AoArrays.EMPTY_STRING_ARRAY;
							Arrays.sort(list);
							currentLists.push(list);
							currentIndexes.push(0);
						}
					} catch(FileNotFoundException e) {
						err.println("Error trying to access file: " + root + filename);
						err.flush();
						throw e;
					}
				}
				return filename;
			}
		}

		private boolean containsFile(
			Map<Integer,Set<String>> osVersions,
			Map<Integer,Set<String>> unseenOsVersions,
			OSFilename osFilename
		) {
			Integer I = osFilename.operating_system_version;
			// Flag as found
			synchronized(unseenOsVersions) {
				Set<String> unseenFilenames = unseenOsVersions.get(I);
				if(unseenFilenames != null) unseenFilenames.remove(osFilename.filename);
			}
			// Check if is in list
			Set<String> filenames = osVersions.get(I);
			return
				filenames != null
				&& filenames.contains(osFilename.filename)
			;
		}

		private boolean isConfig(OSFilename osFilename) {
			return containsFile(configs, unseenConfigs, osFilename);
		}

		private boolean isNever(OSFilename osFilename) {
			return containsFile(nevers, unseenNevers, osFilename);
		}

		private boolean isNoRecurse(OSFilename osFilename) {
			if(osFilename.operating_system_version == -1 || osFilename.filename == null) return false;
			return containsFile(noRecurses, unseenNoRecurses, osFilename);
		}

		private boolean isOptional(OSFilename osFilename) {
			return containsFile(optionals, unseenOptionals, osFilename);
		}

		private boolean isPrelink(OSFilename osFilename) {
			return containsFile(prelinks, unseenPrelinks, osFilename);
		}

		private boolean isUser(OSFilename osFilename) {
			if(osFilename.operating_system_version == -1 || osFilename.filename == null) return false;
			return containsFile(users, unseenUsers, osFilename);
		}

		private final Object outputLock = new Object();
		private int outputCount = 0;
		private boolean isFirstOutput = true;

		private void print(String line) {
			synchronized(outputLock) {
				if(outputCount == 0) {
					if(isFirstOutput) isFirstOutput = false;
					else out.print("begin;\n");
				}
				out.print(line); out.print('\n');
				if(++outputCount >= ROWS_PER_TRANSACTION) {
					out.print("commit;\n");
					outputCount = 0;
				}
				out.flush();
			}
		}
	}

	public static class FoundNeversException extends Exception {

		private static final long serialVersionUID = 1L;

		private final Set<String> foundNevers;

		private FoundNeversException(Set<String> foundNevers) {
			super("One or more files exist that are listed in nevers");
			assert !foundNevers.isEmpty();
			this.foundNevers = foundNevers;
		}

		public Set<String> getFoundNevers() {
			return foundNevers;
		}
	}

	public void run(PrintWriter out, PrintWriter err) throws IOException, FoundNeversException, InterruptedException {
		RunState runState = new RunState(out, err);
		// First do a quick scan for nevers
		Set<String> foundNevers = new LinkedHashSet<>();
		Iterator<Integer> operatingSystems = nevers.keySet().iterator();
		while(operatingSystems.hasNext()) {
			Integer OSV = operatingSystems.next();
			int osv = OSV;
			for(String filename : nevers.get(OSV)) {
				String path = getOperatingSystemPath(osv) + filename;
				UnixFile uf = new UnixFile(path);
				if(uf.getStat().exists()) {
					foundNevers.add(path);
				}
			}
		}
		if(!foundNevers.isEmpty()) {
			throw new FoundNeversException(foundNevers);
		} else {
			out.print("create temp table distro_files_tmp (\n"
					+ "  pkey integer\n"
					+ "    not null,\n"
					+ "  operating_system_version integer\n"
					+ "    not null,\n"
					+ "  path text\n"
					+ "    not null,\n"
					+ "  optional bool\n"
					+ "    not null,\n"
					+ "  type text\n"
					+ "    not null,\n"
					+ "  mode int8\n"
					+ "    not null,\n"
					+ "  linux_account text\n"
					+ "    not null,\n"
					+ "  linux_group text\n"
					+ "    not null,\n"
					+ "  size int8,\n"
					+ "  file_md5_hi int8,\n"
					+ "  file_md5_lo int8,\n"
					+ "  symlink_target text\n"
					+ ") without oids;\n"
					+ "begin;\n");
			out.flush();
			DistroGeneratorThread[] generators;
			synchronized(runState.generators) {
				for(int c = 0; c < numThreads; c++) runState.generators[c] = new DistroGeneratorThread(runState, c);
				for(int c = 0; c < numThreads; c++) runState.generators[c].start();
				generators = runState.generators;
			}
			// Join each thread and throw exceptions from them
			for(DistroGeneratorThread generator : generators) {
				generator.join();
				// Throw any exception from the worker thread
				synchronized(generator) {
					if(generator.ioException != null) throw generator.ioException;
					if(generator.foundNeversException != null) throw generator.foundNeversException;
					Throwable t = generator.throwable;
					if(t != null) {
						if(t instanceof RuntimeException) throw (RuntimeException)t;
						throw new RuntimeException(t);
					}
				}
			}
			// Finish the program
			synchronized(runState.outputLock) {
				if(runState.outputCount > 0) runState.out.print("commit;\n");
				runState.out.print("select\n"
						+ "  dft.*\n"
						+ "from\n"
						+ "  distro_files_tmp dft\n"
						+ "  left outer join linux_accounts la on dft.linux_account=la.username\n"
						+ "  left outer join linux_groups lg on dft.linux_group=lg.name\n"
						+ "where\n"
						+ "  la.username is null\n"
						+ "  or lg.name is null\n"
						+ "order by\n"
						+ "  dft.operating_system_version,\n"
						+ "  dft.path\n"
						+ ";\n"
						+ "begin;\n"
						+ "delete from distro_files;\n"
						+ "insert into distro_files select * from distro_files_tmp;\n"
						+ "commit;\n"
						+ "drop table distro_files_tmp;\n"
						+ "vacuum full analyze distro_files;\n");
				runState.out.flush();
			}

			// Report files that in are configs but not found in template
			reportMissingTemplateFiles("configs.txt", runState.unseenConfigs, err);
			reportMissingTemplateFiles("no_recurses.txt", runState.unseenNoRecurses, err);
			reportMissingTemplateFiles("optionals.txt", runState.unseenOptionals, err);
			reportMissingTemplateFiles("prelinks.txt", runState.unseenPrelinks, err);
			reportMissingTemplateFiles("users.txt", runState.unseenUsers, err);
		}
	}

	private class DistroGeneratorThread extends Thread {

		final private RunState runState;

		final private int threadNum;

		private IOException ioException;
		private FoundNeversException foundNeversException;
		private Throwable throwable;

		private DistroGeneratorThread(RunState runState, int threadNum) {
			this.runState = runState;
			this.threadNum = threadNum;
		}

		@Override
		public void run() {
			try {
				final OSFilename osFilename = new OSFilename();
				while(true) {
					String filename = runState.getNextFilename(osFilename);
					if(filename == null) break;
					osFilename.parseValues(filename);
					if(osFilename.operating_system_version != -1 && osFilename.filename != null) {
						if(runState.isNever(osFilename)) {
							throw new FoundNeversException(Collections.singleton(filename));
						}
						// Determine the type
						String type = runState.isUser(osFilename) ? DistroFileType.USER
							: runState.isConfig(osFilename) ? DistroFileType.CONFIG
							: runState.isNoRecurse(osFilename) ? DistroFileType.NO_RECURSE
							: runState.isPrelink(osFilename) ? DistroFileType.PRELINK
							: DistroFileType.SYSTEM
						;

						// Decide if the size should be stored
						UnixFile file = new UnixFile(osFilename.getFullPath(root));
						Stat fileStat = file.getStat();
						long statMode = fileStat.getRawMode();
						boolean isRegularFile = UnixFile.isRegularFile(statMode);
						boolean storeSize = isRegularFile && !runState.isConfig(osFilename);

						// Only hash system regular files
						boolean doHash = isRegularFile && (type.equals(DistroFileType.SYSTEM) || type.equals(DistroFileType.PRELINK));

						try {
							StringBuilder SB = new StringBuilder();
							SB
								.append("insert into distro_files_tmp values (nextval('distro_files_pkey_seq'), ")
								.append(osFilename.operating_system_version)
								.append(", E'")
								.append(SQLUtility.escapeSQL(osFilename.filename.length() == 0 ? "/" : osFilename.filename))
								.append("', ")
								.append(runState.isOptional(osFilename) ? "true" : "false")
								.append(", '")
								.append(type)
								.append("', ")
								.append(statMode)
								.append("::int8, E'")
								.append(SQLUtility.escapeSQL(getUsername(osFilename, fileStat.getUid())))
								.append("', E'")
								.append(SQLUtility.escapeSQL(getGroupname(osFilename, fileStat.getGid())))
								.append("', ");
							if(storeSize) {
								SB.append(fileStat.getSize()).append("::int8");
							} else {
								SB.append("null");
							}
							SB.append(", ");
							if(doHash) {
								if(type.equals(DistroFileType.SYSTEM)) {
									byte[] md5 = MD5Utils.md5(osFilename.getFullPath(root));
									SB.append(MD5.getMD5Hi(md5)).append("::int8, ").append(MD5.getMD5Lo(md5)).append("::int8");
								} else if(type.equals(DistroFileType.PRELINK)) {
									String chroot = root + '/' + osFilename.getOSName() + '/' + osFilename.getOSVersion() + '/' + osFilename.getOSArchitecture();
									String[] prelinkMd5Command = {
										"/usr/sbin/chroot",
										chroot,
										"/usr/sbin/prelink",
										"--verify",
										"--md5",
										osFilename.filename
									};
									try {
										Process P = Runtime.getRuntime().exec(prelinkMd5Command);
										try {
											P.getOutputStream().close();
											try (BufferedReader in = new BufferedReader(new InputStreamReader(P.getInputStream()))) {
												String line = in.readLine();
												if(line.length() < 32) throw new IOException("Line too short, must be at least 32 characters: " + line);
												String md5 = line.substring(0, 32);
												SB.append(MD5.getMD5Hi(md5)).append("::int8, ").append(MD5.getMD5Lo(md5)).append("::int8");
											}
										} finally {
											try {
												int retCode = P.waitFor();
												if(retCode != 0) throw new IOException("Non-zero response from command: " + AOServDaemon.getCommandString(prelinkMd5Command));
											} catch(InterruptedException err) {
												// Restore the interrupted status
												Thread.currentThread().interrupt();
												IOException ioErr = new InterruptedIOException();
												ioErr.initCause(err);
												throw ioErr;
											}
										}
									} catch(IOException e) {
										runState.err.println("Undoing prelink on \"" + osFilename.filename + "\": " + e.toString());
										runState.err.flush();
										AOServDaemon.exec(
											"/usr/sbin/chroot",
											chroot,
											"/usr/sbin/prelink",
											"--undo",
											osFilename.filename
										);

										// Try again after undo
										Process P = Runtime.getRuntime().exec(prelinkMd5Command);
										try {
											P.getOutputStream().close();
											try (BufferedReader in = new BufferedReader(new InputStreamReader(P.getInputStream()))) {
												String line = in.readLine();
												if(line.length() < 32) throw new IOException("Line too short, must be at least 32 characters: " + line);
												String md5 = line.substring(0, 32);
												SB.append(MD5.getMD5Hi(md5)).append("::int8, ").append(MD5.getMD5Lo(md5)).append("::int8");
											}
										} finally {
											try {
												int retCode = P.waitFor();
												if(retCode != 0) throw new IOException("Non-zero response from command: " + AOServDaemon.getCommandString(prelinkMd5Command));
											} catch(InterruptedException err2) {
												// Restore the interrupted status
												Thread.currentThread().interrupt();
												IOException ioErr = new InterruptedIOException();
												ioErr.initCause(err2);
												throw ioErr;
											}
										}
									}
								} else throw new RuntimeException("Unexpected value for type: " + type);
							} else {
								SB.append("null, null");
							}
							SB.append(", ");
							if(UnixFile.isSymLink(statMode)) {
								SB.append("E'").append(SQLUtility.escapeSQL(file.readLink())).append('\'');
							} else {
								SB.append("null");
							}
							SB.append(");");
							runState.print(SB.toString());
						} catch(IOException e) {
							runState.err.println("Error on file: " + osFilename.getFullPath(root));
							runState.err.flush();
							throw e;
						}
					}
				}
			} catch(IOException e) {
				synchronized(this) {
					this.ioException = e;
				}
			} catch(FoundNeversException e) {
				synchronized(this) {
					this.foundNeversException = e;
				}
			} catch(ThreadDeath TD) {
				throw TD;
			} catch(Throwable t) {
				synchronized(this) {
					this.throwable = t;
				}
			}
		}
	}

	public static int getOperatingSystemVersion(String name, String version, String architecture) {
		if(name.equals(OperatingSystem.CENTOS)) {
			if(
				version.equals(OperatingSystemVersion.VERSION_5)
				&& architecture.equals(Architecture.I686_AND_X86_64)
			) {
				return OperatingSystemVersion.CENTOS_5_I686_AND_X86_64;
			}
			if(
				version.equals(OperatingSystemVersion.VERSION_5_DOM0)
				&& architecture.equals(Architecture.X86_64)
			) {
				return OperatingSystemVersion.CENTOS_5_DOM0_X86_64;
			}
			if(
				version.equals(OperatingSystemVersion.VERSION_7)
				&& architecture.equals(Architecture.X86_64)
			) {
				return OperatingSystemVersion.CENTOS_7_X86_64;
			}
			if(
				version.equals(OperatingSystemVersion.VERSION_7_DOM0)
				&& architecture.equals(Architecture.X86_64)
			) {
				return OperatingSystemVersion.CENTOS_7_DOM0_X86_64;
			}
		} else if(name.equals(OperatingSystem.REDHAT)) {
			if(
				version.equals(OperatingSystemVersion.VERSION_ES_4)
				&& architecture.equals(Architecture.X86_64)
			) {
				return OperatingSystemVersion.REDHAT_ES_4_X86_64;
			}
		}
		throw new RuntimeException("Unsupported operating system: name=" + name + ", version=" + version+", architecture=" + architecture);
	}

	private static void reportMissingTemplateFiles(String filename, Map<Integer,Set<String>> lists, PrintWriter err) {
		boolean reportedOne = false;
		List<Integer> osvs = sort(lists.keySet().iterator());
		for(Integer OSV : osvs) {
			int osv = OSV;
			List<String> filenames = sort(lists.get(OSV).iterator());
			for(String path : filenames) {
				if(!reportedOne) {
					reportedOne = true;
					err.println();
					err.println("*************************************************************************");
					err.print("* WARNING: These files are listed in ");
					err.println(filename);
					err.println("* but not found in the distribution template");
					err.println("*************************************************************************");
				}
				if(osv == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) err.print("centos/5/i686,x86_64");
				else if(osv == OperatingSystemVersion.CENTOS_5_DOM0_X86_64) err.print("centos/5.dom0/86_64");
				else if(osv == OperatingSystemVersion.CENTOS_7_X86_64) err.print("centos/7/x86_64");
				else if(osv == OperatingSystemVersion.CENTOS_7_DOM0_X86_64) err.print("centos/7.dom0/86_64");
				else if(osv == OperatingSystemVersion.REDHAT_ES_4_X86_64) err.print("redhat/ES 4/x86_64");
				else throw new RuntimeException("Unknown value for osv: " + osv);
				err.println(path);
				err.flush();
			}
		}
	}

	private String getOperatingSystemPath(int osv) {
		if(osv == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) return root + "/centos/5/i686,x86_64";
		if(osv == OperatingSystemVersion.CENTOS_5_DOM0_X86_64) return root + "/centos/5.dom0/x86_64";
		if(osv == OperatingSystemVersion.CENTOS_7_X86_64) return root + "/centos/7/x86_64";
		if(osv == OperatingSystemVersion.CENTOS_7_DOM0_X86_64) return root + "/centos/7.dom0/x86_64";
		if(osv == OperatingSystemVersion.REDHAT_ES_4_X86_64) return root + "/redhat/ES 4/x86_64";
		else throw new RuntimeException("Unknown value for osv: " + osv);
	}

	private static <T extends Comparable<? super T>> List<T> sort(Iterator<T> I) {
		List<T> list = new ArrayList<>();
		while(I.hasNext()) list.add(I.next());
		Collections.sort(list);
		return list;
	}
}
