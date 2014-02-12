/*
 * Copyright 2001-2014 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.distro;

import com.aoindustries.aoserv.client.Architecture;
import com.aoindustries.aoserv.client.DistroFileType;
import com.aoindustries.aoserv.client.OperatingSystem;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
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
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EmptyStackException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Stack;

/**
 * Creates the server distribution database contents.
 *
 * @author  AO Industries, Inc.
 */
final public class DistroGenerator extends Thread {

	private static final int NUM_THREADS=4;

	private static final int ROWS_PER_TRANSACTION=1000;

	private static final DistroGenerator[] generators=new DistroGenerator[NUM_THREADS];
	private static int activeGeneratorCount=0;

	private static final ChainWriter out=new ChainWriter(System.out);

	private static String root = "/distro";

	/**
	 * HashMap of Integer->HashMap(String filename, Boolean wasFound).
	 */
	private static Map<Integer,Map<String,Boolean>>
		sortedConfigs,
		sortedNevers,
		sortedNo_recurses,
		sortedOptionals,
		sortedPrelinks,
		sortedUsers
	;

	private static boolean isConfig(OSFilename osFilename) throws IOException {
		synchronized(DistroGenerator.class) {
			if(sortedConfigs==null) sortedConfigs=loadFileList("configs");
			return containsFile(sortedConfigs, osFilename);
		}
	}

	private static Map<Integer,Map<String,Boolean>> getNevers() throws IOException {
		synchronized(DistroGenerator.class) {
			if(sortedNevers==null) sortedNevers=loadFileList("nevers");
			return sortedNevers;
		}
	}

	private static boolean isNever(OSFilename osFilename) throws IOException {
		return containsFile(getNevers(), osFilename);
	}

	private static boolean isNoRecurse(OSFilename osFilename) throws IOException {
		if(osFilename.operating_system_version==-1 || osFilename.filename==null) return false;
		synchronized(DistroGenerator.class) {
			if(sortedNo_recurses==null) sortedNo_recurses=loadFileList("no_recurses");
			return containsFile(sortedNo_recurses, osFilename);
		}
	}

	private static boolean isOptional(OSFilename osFilename) throws IOException {
		synchronized(DistroGenerator.class) {
			if(sortedOptionals==null) sortedOptionals=loadFileList("optionals");
			return containsFile(sortedOptionals, osFilename);
		}
	}

	private static boolean isPrelink(OSFilename osFilename) throws IOException {
		synchronized(DistroGenerator.class) {
			if(sortedPrelinks==null) sortedPrelinks=loadFileList("prelinks");
			return containsFile(sortedPrelinks, osFilename);
		}
	}

	private static boolean isUser(OSFilename osFilename) throws IOException {
		if(osFilename.operating_system_version==-1 || osFilename.filename==null) return false;
		synchronized(DistroGenerator.class) {
			if(sortedUsers==null) sortedUsers=loadFileList("users");
			return containsFile(sortedUsers, osFilename);
		}
	}

	private static Map<Integer,Map<String,Boolean>> loadFileList(String name) throws IOException {
		Map<Integer,Map<String,Boolean>> osVersions=new HashMap<>();
		BufferedReader in=new BufferedReader(new InputStreamReader(DistroGenerator.class.getResourceAsStream(name+".txt")));
		try {
			String line;
			while((line=in.readLine())!=null) {
				if(line.length()>0 && line.charAt(0)!='#') {
					int pos1=line.indexOf('/');
					if(pos1==-1) throw new IOException("cannot find first slash (/) in distro file named "+name+", line="+line);
					int pos2=line.indexOf('/', pos1+1);
					if(pos2==-1) throw new IOException("cannot find second slash (/) in distro file named "+name+", line="+line);
					int pos3=line.indexOf('/', pos2+1);
					if(pos3==-1) throw new IOException("cannot find third slash (/) in distro file named "+name+", line="+line);
					int osVersion=getOperatingSystemVersion(
						line.substring(0, pos1),
						line.substring(pos1+1, pos2),
						line.substring(pos2+1, pos3)
					);
					String filename=line.substring(pos3);
					Integer I=Integer.valueOf(osVersion);
					Map<String,Boolean> filenames=osVersions.get(I);
					if(filenames==null) osVersions.put(I, filenames=new HashMap<>());
					filenames.put(filename, Boolean.FALSE);
				}
			}
		} finally {
			in.close();
		}
		return osVersions;
	}

	private static boolean containsFile(Map<Integer,Map<String,Boolean>> osVersions, OSFilename osFilename) throws IOException {
		Integer I=Integer.valueOf(osFilename.operating_system_version);
		Map<String,Boolean> filenames=osVersions.get(I);
		if(filenames==null) return false;
		Boolean B=filenames.get(osFilename.filename);
		if(B==null) return false;
		if(B.equals(Boolean.FALSE)) filenames.put(osFilename.filename, Boolean.TRUE);
		return true;
	}

	private static Map<Integer,Map<Integer,String>> usernames;
	private static String getUsername(OSFilename osFilename, int fileUID) throws IOException {
		synchronized(DistroGenerator.class) {
			if (usernames==null) usernames=new HashMap<>();
			Integer I=Integer.valueOf(osFilename.operating_system_version);
			Map<Integer,String> realNames=usernames.get(I);
			if(realNames==null) {
				usernames.put(I, realNames=new HashMap<>());
				BufferedReader in = new BufferedReader(
					new InputStreamReader(
						new BufferedInputStream(
							new FileInputStream(
								new UnixFile(
									root+'/'+osFilename.getOSName()+'/'+osFilename.getOSVersion()+'/'+osFilename.getOSArchitecture()+"/etc/passwd"
								).getFile()
							)
						)
					)
				);
				try {
					String line;
					while ((line=in.readLine())!=null) {
						List<String> fields = StringUtility.splitString(line, ':');
						String username = fields.get(0);
						Integer userID = Integer.valueOf(Integer.parseInt(fields.get(2)));
						if(!realNames.containsKey(userID)) realNames.put(userID, username);
					}
				} finally {
					in.close();
				}
			}
			String username=realNames.get(Integer.valueOf(fileUID));
			if(username==null) throw new IOException("Unable to find username: "+fileUID + " for file " + osFilename);
			return username;
		}
	}

	private static Map<Integer,Map<Integer,String>> groupnames;
	private static String getGroupname(OSFilename osFilename, int fileGID) throws IOException {
		synchronized(DistroGenerator.class) {
			if (groupnames==null) groupnames=new HashMap<>();
			Integer I=Integer.valueOf(osFilename.operating_system_version);
			Map<Integer,String> realGroups=groupnames.get(I);
			if(realGroups==null) {
				groupnames.put(I, realGroups=new HashMap<>());
				BufferedReader in = new BufferedReader(
					new InputStreamReader(
						new BufferedInputStream(
							new FileInputStream(
								new UnixFile(
									root+'/'+osFilename.getOSName()+'/'+osFilename.getOSVersion()+'/'+osFilename.getOSArchitecture()+"/etc/group"
								).getFile()
							)
						)
					)
				);
				try {
					String line;
					while ((line=in.readLine())!=null) {
						List<String> fields = StringUtility.splitString(line, ':');
						String groupname = fields.get(0);
						Integer groupID = Integer.valueOf(Integer.parseInt(fields.get(2)));
						if(!realGroups.containsKey(groupID)) realGroups.put(groupID, groupname);
					}
				} finally {
					in.close();
				}
			}
			String groupname=realGroups.get(Integer.valueOf(fileGID));
			if(groupname==null) throw new IOException("Unable to find group name "+fileGID + " for file " + osFilename);
			return groupname;
		}
	}

	public static void main(String[] args) {
		try {
			if (args.length>0) root = args[0];

			// First do a quick scan for nevers
			boolean foundNever=false;
			Map<Integer,Map<String,Boolean>> nevers=getNevers();
			Iterator<Integer> operatingSystems=nevers.keySet().iterator();
			while(operatingSystems.hasNext()) {
				Integer OSV=operatingSystems.next();
				int osv=OSV.intValue();
				Iterator<String> filenames=nevers.get(OSV).keySet().iterator();
				while(filenames.hasNext()) {
					String filename=getOperatingSystemPath(osv)+filenames.next();
					UnixFile uf=new UnixFile(filename);
					if(uf.getStat().exists()) {
						System.err.println("File exists but is listed in nevers: "+filename);
						System.err.flush();
						foundNever=true;
					}
				}
			}
			if(foundNever) {
				System.exit(2);
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
				synchronized(DistroGenerator.class) {
					for(int c=0;c<NUM_THREADS;c++) generators[c]=new DistroGenerator(c);
					activeGeneratorCount=NUM_THREADS;
					for(int c=0;c<NUM_THREADS;c++) generators[c].start();
				}
			}
		} catch(IOException err) {
			ErrorPrinter.printStackTraces(err);
			System.exit(1);
		}
	}

	private static final Object nextFilenameLock=new Object();

	private static Stack<String> currentDirectories;
	private static Stack<String[]> currentLists;
	private static Stack<Integer> currentIndexes;

	private static boolean done=false;
	private String getNextFilename(OSFilename temp) throws IOException {
		synchronized(nextFilenameLock) {
			String filename;

			if(done) filename=null;
			else {
				// Initialize the stacks, if needed
				if(currentDirectories==null) {
					(currentDirectories=new Stack<>()).push("");
					(currentLists=new Stack<>()).push(new String[] {""});
					(currentIndexes=new Stack<>()).push(Integer.valueOf(0));
				}
			}
			String currentDirectory=null;
			String[] currentList=null;
			int currentIndex=-1;
			try {
				currentDirectory=currentDirectories.peek();
				currentList=currentLists.peek();
				currentIndex=currentIndexes.peek().intValue();

				// Undo the stack as far as needed
				while(currentDirectory!=null && currentIndex>=currentList.length) {
					currentDirectories.pop();
					currentDirectory=currentDirectories.peek();
					currentLists.pop();
					currentList=currentLists.peek();
					currentIndexes.pop();
					currentIndex=currentIndexes.peek().intValue();
				}
			} catch(EmptyStackException err) {
				currentDirectory=null;
			}
			if(currentDirectory==null) {
				filename=null;
				done=true;
			} else {
				// Get the current filename
				if(currentDirectory.equals("/")) filename="/"+currentList[currentIndex++];
				else filename=currentDirectory+'/'+currentList[currentIndex++];

				// Set to the next file
				currentIndexes.pop();
				currentIndexes.push(Integer.valueOf(currentIndex));

				// Recurse for directories
				try {
					UnixFile unixFile=new UnixFile(root+filename);
					long statMode=unixFile.getStat().getRawMode();
					temp.parseValues(filename);
					if(
						!UnixFile.isSymLink(statMode)
						&& UnixFile.isDirectory(statMode)
						&& !isUser(temp)
						&& !isNoRecurse(temp)
					) {
						// Push on stacks for next level
						currentDirectories.push(filename);
						String[] list=unixFile.list();
						if(list==null) list = AoArrays.EMPTY_STRING_ARRAY;
						Arrays.sort(list);
						currentLists.push(list);
						currentIndexes.push(Integer.valueOf(0));
					}
				} catch(FileNotFoundException err) {
					System.err.println("Error trying to access file: "+root+filename);
					System.err.flush();
					throw err;
				}
			}
			return filename;
		}
	}

	private static final Object outputLock=new Object();
	private static int outputCount=0;
	private static boolean isFirstOutput=true;
	private void print(String line1) {
		synchronized(outputLock) {
			if(outputCount==0) {
				if(isFirstOutput) isFirstOutput=false;
				else out.print("begin;\n");
			}
			out.print(line1).print('\n');
			if(++outputCount>=ROWS_PER_TRANSACTION) {
				out.print("commit;\n");
				outputCount=0;
			}
			out.flush();
		}
	}

	final private int threadNum;

	private DistroGenerator(int threadNum) {
		this.threadNum=threadNum;
	}

	@Override
	public void run() {
		try {
			final OSFilename osFilename=new OSFilename();
			while(true) {
				String filename=getNextFilename(osFilename);
				if(filename==null) break;
				osFilename.parseValues(filename);
				if(osFilename.operating_system_version!=-1 && osFilename.filename!=null) {
					if(isNever(osFilename)) {
						System.err.println("Found a file in the distribution template that should never exist: "+osFilename);
						System.err.flush();
						System.exit(1);
					}
					// Determine the type
					String type=isUser(osFilename)?DistroFileType.USER
						:isConfig(osFilename)?DistroFileType.CONFIG
						:isNoRecurse(osFilename)?DistroFileType.NO_RECURSE
						:isPrelink(osFilename)?DistroFileType.PRELINK
						:DistroFileType.SYSTEM
					;

					// Decide if the size should be stored
					UnixFile file = new UnixFile(osFilename.getFullPath(root));
					Stat fileStat = file.getStat();
					long statMode=fileStat.getRawMode();
					boolean isRegularFile=UnixFile.isRegularFile(statMode);
					boolean storeSize=isRegularFile && !isConfig(osFilename);

					// Only hash system regular files
					boolean doHash=isRegularFile && (type.equals(DistroFileType.SYSTEM) || type.equals(DistroFileType.PRELINK));

					try {
						StringBuilder SB=new StringBuilder();
						SB
							.append("insert into distro_files_tmp values (nextval('distro_files_pkey_seq'), ")
							.append(osFilename.operating_system_version)
							.append(", E'")
							.append(SQLUtility.escapeSQL(osFilename.filename.length()==0?"/":osFilename.filename))
							.append("', ")
							.append(isOptional(osFilename)?"true":"false")
							.append(", '")
							.append(type)
							.append("', ")
							.append(statMode)
							.append("::int8, E'")
							.append(SQLUtility.escapeSQL(getUsername(osFilename, fileStat.getUid())))
							.append("', E'")
							.append(SQLUtility.escapeSQL(getGroupname(osFilename, fileStat.getGid())))
							.append("', ");
						if(storeSize) SB.append(fileStat.getSize()).append("::int8");
						else SB.append("null");
						SB.append(", ");
						if(doHash) {
							if(type.equals(DistroFileType.SYSTEM)) {
								byte[] md5=MD5Utils.md5(osFilename.getFullPath(root));
								SB.append(MD5.getMD5Hi(md5)).append("::int8, ").append(MD5.getMD5Lo(md5)).append("::int8");
							} else if(type.equals(DistroFileType.PRELINK)) {
								String chroot = root+'/'+osFilename.getOSName()+'/'+osFilename.getOSVersion()+'/'+osFilename.getOSArchitecture();
								String[] command = {
									"/usr/sbin/chroot",
									chroot,
									"/usr/sbin/prelink",
									"--verify",
									"--md5",
									osFilename.filename
								};
								try {
									Process P = Runtime.getRuntime().exec(command);
									try {
										P.getOutputStream().close();
										BufferedReader in = new BufferedReader(new InputStreamReader(P.getInputStream()));
										try {
											String line = in.readLine();
											if(line.length()<32) throw new IOException("Line too short, must be at least 32 characters: "+line);
											String md5 = line.substring(0, 32);
											SB.append(MD5.getMD5Hi(md5)).append("::int8, ").append(MD5.getMD5Lo(md5)).append("::int8");
										} finally {
											in.close();
										}
									} finally {
										try {
											int retCode = P.waitFor();
											if(retCode!=0) throw new IOException("Non-zero response from command: "+AOServDaemon.getCommandString(command));
										} catch(InterruptedException err) {
											IOException ioErr = new InterruptedIOException();
											ioErr.initCause(err);
											throw ioErr;
										}
									}
								} catch(IOException err) {
									System.err.println("Undoing prelink on \""+osFilename.filename+"\": "+err.toString());
									String[] undoCommand = {
										"/usr/sbin/chroot",
										chroot,
										"/usr/sbin/prelink",
										"--undo",
										osFilename.filename
									};
									AOServDaemon.exec(undoCommand);

									// Try again after undo
									Process P = Runtime.getRuntime().exec(command);
									try {
										P.getOutputStream().close();
										BufferedReader in = new BufferedReader(new InputStreamReader(P.getInputStream()));
										try {
											String line = in.readLine();
											if(line.length()<32) throw new IOException("Line too short, must be at least 32 characters: "+line);
											String md5 = line.substring(0, 32);
											SB.append(MD5.getMD5Hi(md5)).append("::int8, ").append(MD5.getMD5Lo(md5)).append("::int8");
										} finally {
											in.close();
										}
									} finally {
										try {
											int retCode = P.waitFor();
											if(retCode!=0) throw new IOException("Non-zero response from command: "+AOServDaemon.getCommandString(command));
										} catch(InterruptedException err2) {
											IOException ioErr = new InterruptedIOException();
											ioErr.initCause(err2);
											throw ioErr;
										}
									}
								}
							} else throw new RuntimeException("Unexpected value for type: "+type);
						} else SB.append("null, null");
						SB.append(", ");
						if(UnixFile.isSymLink(statMode)) SB.append("E'").append(SQLUtility.escapeSQL(file.readLink())).append('\'');
						else SB.append("null");
						SB.append(");");
						print(SB.toString());
					} catch(IOException err) {
						System.err.println("Error on file: "+osFilename.getFullPath(root));
						System.err.flush();
						throw err;
					}
				}
			}

			// Thread done
			synchronized(DistroGenerator.class) {
				generators[threadNum]=null;
				activeGeneratorCount--;
				if(activeGeneratorCount<=0) {
					// Finish the program
					if(outputCount>0) out.print("commit;\n");
					out.print("select\n"
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
					out.flush();

					// Report files that in are configs but not found in template
					reportMissingTemplateFiles("configs.txt", sortedConfigs);
					reportMissingTemplateFiles("no_recurses.txt", sortedNo_recurses);
					reportMissingTemplateFiles("optionals.txt", sortedOptionals);
					reportMissingTemplateFiles("prelinks.txt", sortedPrelinks);
					reportMissingTemplateFiles("users.txt", sortedUsers);
				}
			}
		} catch(IOException err) {
			ErrorPrinter.printStackTraces(err);
			System.exit(1);
		}
	}

	public static int getOperatingSystemVersion(String name, String version, String architecture) {
		if(
			name.equals(OperatingSystem.REDHAT)
			&& version.equals(OperatingSystemVersion.VERSION_ES_4)
			&& architecture.equals(Architecture.X86_64)
		) return OperatingSystemVersion.REDHAT_ES_4_X86_64;
		if(
			name.equals(OperatingSystem.CENTOS)
			&& version.equals(OperatingSystemVersion.VERSION_5)
			&& architecture.equals(Architecture.I686_AND_X86_64)
		) return OperatingSystemVersion.CENTOS_5_I686_AND_X86_64;
		if(
			name.equals(OperatingSystem.CENTOS)
			&& version.equals(OperatingSystemVersion.VERSION_5_DOM0)
			&& architecture.equals(Architecture.X86_64)
		) return OperatingSystemVersion.CENTOS_5_DOM0_X86_64;
		throw new RuntimeException("Unsupported operating system: name="+name+", version="+version+", architecture="+architecture);
	}

	public static void reportMissingTemplateFiles(String filename, Map<Integer,Map<String,Boolean>> lists) {
		boolean reportedOne=false;
		List<Integer> osvs=sort(lists.keySet().iterator());
		for(int c=0;c<osvs.size();c++) {
			Integer OSV=osvs.get(c);
			int osv=OSV.intValue();
			Map<String,Boolean> filenameMap=lists.get(OSV);
			List<String> filenames=sort(filenameMap.keySet().iterator());
			for(int d=0;d<filenames.size();d++) {
				String path=(String)filenames.get(d);
				boolean found=((Boolean)filenameMap.get(path)).booleanValue();
				if(!found) {
					if(!reportedOne) {
						reportedOne=true;
						System.err.println();
						System.err.println("*************************************************************************");
						System.err.print("* WARNING: These files are listed in ");
						System.err.println(filename);
						System.err.println("* but not found in the distribution template");
						System.err.println("*************************************************************************");
					}
					if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) System.err.print("centos/5/i686,x86_64");
					else if(osv==OperatingSystemVersion.CENTOS_5_DOM0_X86_64) System.err.print("centos/5.dom0/86_64");
					else if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) System.err.print("redhat/ES 4/x86_64");
					else throw new RuntimeException("Unknown value for osv: "+osv);
					System.err.println(path);
					System.err.flush();
				}
			}
		}
	}

	private static String getOperatingSystemPath(int osv) {
		if(osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) return root+"/centos/5/i686,x86_64";
		if(osv==OperatingSystemVersion.CENTOS_5_DOM0_X86_64) return root+"/centos/5.dom0/x86_64";
		if(osv==OperatingSystemVersion.REDHAT_ES_4_X86_64) return root+"/redhat/ES 4/x86_64";
		else throw new RuntimeException("Unknown value for osv: "+osv);
	}

	private static <T extends Comparable<? super T>> List<T> sort(Iterator<T> I) {
		List<T> list=new ArrayList<>();
		while(I.hasNext()) list.add(I.next());
		Collections.sort(list);
		return list;
	}
}
