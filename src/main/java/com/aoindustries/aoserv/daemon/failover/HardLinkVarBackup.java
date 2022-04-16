/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2006-2013, 2015, 2017, 2020, 2021, 2022  AO Industries, Inc.
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

package com.aoindustries.aoserv.daemon.failover;

import com.aoapps.collections.AoCollections;
import com.aoapps.hodgepodge.io.FilesystemIterator;
import com.aoapps.hodgepodge.io.FilesystemIteratorRule;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.io.posix.Stat;
import com.aoapps.lang.Strings;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Iterates through all files in /var/backup and creates hard links two or more matching files as follows:
 * <ol>
 *   <li>Both are in /var/backup/hostname/####-##-## filenames (no partial or deleted)</li>
 *   <li>Are in different /var/backup/hostname/####-##-## directories</li>
 *   <li>Have the same exact path relative to their /var/backup/hostname/####-##-## directories</li>
 *   <li>Are on the same underlying partition</li>
 *   <li>Are both regular files</li>
 *   <li>Are not already linked together</li>
 *   <li>Have matching length</li>
 *   <li>Have matching modified time</li>
 *   <li>Have matching permissions</li>
 *   <li>Have matching ownership</li>
 *   <li>Have exactly matching file contents</li>
 * </ol>
 * This is accomplished by iterating through all /var/backup/hostname/####-##-## directories concurrently in a sorted manner such
 * that each unique path is examined once.  For each unique path, all directories having a file at that path will be compared by
 * the above rules.
 * <p>
 * During a pass, the /var/backup/hostname/####-##-## may be renamed to .partial or .deleted by the backup server.  Should this happen
 * either a <code>FileNotFoundException</code> will be thrown or a directory listing will be <code>null</code> or empty.  In any of
 * these cases, the system will continue linking the other directories without interruption.
 * <p>
 * Through the complete pass, statistics will be kept.  The statistics will be displayed every <code>DISPLAY_INTERVAL</code> milliseconds.  These will include:
 * <ul>
 *   <li>Total number of filesystem objects (unique paths from /var/backup)</li>
 *   <li>Total number of unique paths</li>
 *   <li>Total number of directories</li>
 *   <li>Total number of files</li>
 *   <li>Total number of symbolic links</li>
 *   <li>Total number of new hard links created</li>
 *   <li>Total number of bytes in all files</li>
 *   <li>Total number of bytes saved by new hard links (not disk blocks, just file bytes)</li>
 * </ul>
 * @author  AO Industries, Inc.
 */
public final class HardLinkVarBackup {

	private static final int DISPLAY_INTERVAL = 10000;

	@SuppressWarnings("UnusedAssignment")
	public static void main(String[] args) {
		final boolean debug = args.length>0 && args[0].equalsIgnoreCase("debug");
		@SuppressWarnings("UseOfSystemOutOrSystemErr")
		final PrintStream out = System.out;
		@SuppressWarnings("UseOfSystemOutOrSystemErr")
		final PrintStream err = System.err;

		// Keep statistics during the pass
		long totalObjects = 0;
		long totalUniquePaths = 0;
		long totalDirectories = 0;
		long totalFiles = 0;
		long totalSymlinks = 0;
		long newHardLinkCount = 0;
		long totalNumberFileBytes = 0;
		long bytesSaved = 0;

		// Find all the candidate directories under /var/backup
		File varBackup = new File("/var/backup");
		String[] serversList = varBackup.list();
		if(serversList!=null && serversList.length>0) {
			Arrays.sort(serversList);
			List<FilesystemIterator> iteratorList = new ArrayList<>();
			List<String> iteratorStartsList = new ArrayList<>();
			for(String serverDir : serversList) {
				File datesDir = new File(varBackup, serverDir);
				String[] datesList = datesDir.list();
				if(datesList!=null) {
					Arrays.sort(datesList);
					for(String datesFilename : datesList) {
						if(
							datesFilename.length()==10
							&& datesFilename.charAt(0)>='0' && datesFilename.charAt(0)<='9'
							&& datesFilename.charAt(1)>='0' && datesFilename.charAt(1)<='9'
							&& datesFilename.charAt(2)>='0' && datesFilename.charAt(2)<='9'
							&& datesFilename.charAt(3)>='0' && datesFilename.charAt(3)<='9'
							&& datesFilename.charAt(4)=='-'
							&& datesFilename.charAt(5)>='0' && datesFilename.charAt(5)<='9'
							&& datesFilename.charAt(6)>='0' && datesFilename.charAt(6)<='9'
							&& datesFilename.charAt(7)=='-'
							&& datesFilename.charAt(8)>='0' && datesFilename.charAt(8)<='9'
							&& datesFilename.charAt(9)>='0' && datesFilename.charAt(9)<='9'
						) {
							String path = "/var/backup/"+serverDir+"/"+datesFilename;
							PosixFile uf = new PosixFile(path);
							try {
								if(uf.getStat().isDirectory()) {
									out.print("Scanning "); out.println(path);
									Map<String, FilesystemIteratorRule> rules = Collections.singletonMap(path, FilesystemIteratorRule.OK);
									Map<String, FilesystemIteratorRule> prefixRules = Collections.emptyMap();
									iteratorStartsList.add(path);
									iteratorList.add(new FilesystemIterator(rules, prefixRules, path));
								}
							} catch(IOException ioExc) {
								ioExc.printStackTrace(err);
							}
						}
					}
				}
			}

			final int numIterators = iteratorList.size();
			final FilesystemIterator[] iterators = iteratorList.toArray(new FilesystemIterator[numIterators]);
			final String[] iteratorStarts = iteratorStartsList.toArray(new String[numIterators]);
			final PosixFile[] unixFiles = new PosixFile[numIterators];
			final String[] relativePaths = new String[numIterators];
			// For quicker comparisons, matches for lowest relative paths are cached here
			final boolean[] isLowestRelativePaths = new boolean[numIterators];

			// Save RAM
			serversList=null;
			varBackup=null;
			iteratorList=null;

			// Start all on first path
			for(int c=0;c<numIterators;c++) {
				try {
					File file = iterators[c].getNextFile();
					PosixFile uf = file==null ? null : new PosixFile(file);
					unixFiles[c]=uf;
					relativePaths[c]=uf==null ? null : uf.getPath().substring(iteratorStarts[c].length());
				} catch(IOException ioExc) {
					ioExc.printStackTrace(err);
				}
			}

			// During each unique path, the links created are tracked to improve performance when source and destination devices and inodes match
			final Map<LinkKey, String> links = AoCollections.newHashMap(numIterators);

			// When two files are compared but don't match, they are added here to avoid repetative comparisons for the same device/inode pairs
			final Set<LinkKey> contentNotEquals = AoCollections.newHashSet(numIterators);

			long lastDisplayTime = System.currentTimeMillis();

			// Iterate through all files in order (ordering provided by the filesystem iterators)
			while(true) {
				// Find the lowest relative filename (lexically), skipping the /var/backup/hostname/####-##-## part
				String lowestRelativePath = null;
				for(int c=0;c<numIterators;c++) {
					if(unixFiles[c]!=null) {
						String relativePath = relativePaths[c];
						if(lowestRelativePath==null || relativePath.compareTo(lowestRelativePath)<0) {
							lowestRelativePath=relativePath;
						}
					}
				}
				// If none found, then we are done
				if(lowestRelativePath==null) break;

				// Clear links for this unique path
				links.clear();

				// Clears not equals for this unique path
				contentNotEquals.clear();

				for(int c=0;c<numIterators;c++) {
					PosixFile uf = unixFiles[c];
					if(uf!=null) {
						if(isLowestRelativePaths[c]=(relativePaths[c].equals(lowestRelativePath))) {
							// Keep stats
							totalObjects++;
							try {
								Stat ufStat = uf.getStat();
								long statMode = ufStat.getRawMode();
								long mode = statMode & PosixFile.PERMISSION_MASK;
								if(PosixFile.isDirectory(statMode)) totalDirectories++;
								else if(PosixFile.isSymLink(statMode)) totalSymlinks++;
								else if(PosixFile.isRegularFile(statMode)) {
									totalFiles++;
									long device = ufStat.getDevice();
									long inode = ufStat.getInode();
									long size = ufStat.getSize();
									if(size==-1) throw new IOException("Size unknown: "+uf);
									totalNumberFileBytes += size;
									long modifyTime = ufStat.getModifyTime();
									int uid = ufStat.getUid();
									int gid = ufStat.getGid();

									// Look for regular file up the list to link to
									for(int d=(c-1);d>=0;d--) {
										PosixFile otherUF = unixFiles[d];
										if(
											otherUF!=null
											// Have the same exact path relative to their /var/backup/hostname/####-##-## directories
											&& isLowestRelativePaths[d] //relativePaths[d].equals(lowestRelativePath)
										) {
											Stat otherUFStat = otherUF.getStat();
											if(
												// Are on the same underlying partition
												device==otherUFStat.getDevice()
												// Are both regular files
												&& otherUFStat.isRegularFile()
												// Are not already linked together
												&& inode!=otherUFStat.getInode()
											) {
												// Have matching length
												long otherSize=otherUFStat.getSize();
												if(otherSize==-1) throw new IOException("Size unknown: "+otherUF);
												if(
													size==otherSize
													// Have matching modified time
													&& modifyTime==otherUFStat.getModifyTime()
													// Have matching permissions
													&& mode==otherUFStat.getMode()
													// Have matching ownership
													&& uid==otherUFStat.getUid()
													&& gid==otherUFStat.getGid()
												) {
													LinkKey linkKey = new LinkKey(device, inode, otherUFStat.getDevice(), otherUFStat.getInode());
													// Has already been linked for this path
													String destination=links.get(linkKey);
													if(destination==null) {
														if(!contentNotEquals.contains(linkKey)) {
															// Have exactly matching file contents
															if(uf.contentEquals(otherUF)) destination=otherUF.getPath();
															else contentNotEquals.add(linkKey);
														}
													}
													if(destination!=null) {
														if(debug) {
															out.println("DEBUG: Would hard link:");
															out.println("    From: "+uf.getPath());
															out.println("    To..: "+destination);
														} else {
															// Do hard link
															// Make link in a temp path and then move into place atomically using renameTo
															// Find any available filename
															PosixFile tempUF = null;
															for(int e=0;e<Integer.MAX_VALUE;e++) {
																String tempPath=uf.getPath()+'.'+e;
																PosixFile temp = new PosixFile(tempPath);
																if(!temp.getStat().exists()) {
																	tempUF=temp;
																	break;
																}
															}
															if(tempUF==null) throw new IOException("Unable to make temp file: "+uf.getPath());
															tempUF.link(destination);
															tempUF.renameTo(uf);
														}
														if(!links.containsKey(linkKey)) {
															links.put(linkKey, destination);
															newHardLinkCount++;
															bytesSaved+=size;
														}
														// Break loop when hardlink done
														break;
													}
												}
											}
										}
									}
								}
							} catch(IOException ioExc) {
								ioExc.printStackTrace(err);
								unixFiles[c]=null;
								relativePaths[c]=null;
								isLowestRelativePaths[c]=false;
							}
						}
					}
				}

				// Iterate each to next file
				for(int c=0;c<numIterators;c++) {
					PosixFile uf = unixFiles[c];
					if(uf!=null) {
						if(isLowestRelativePaths[c]) {
						//if(relativePaths[c].equals(lowestRelativePath)) {
							try {
								File file = iterators[c].getNextFile();
								PosixFile newUf = file==null ? null : new PosixFile(file);
								unixFiles[c]=newUf;
								relativePaths[c]=newUf==null ? null : newUf.getPath().substring(iteratorStarts[c].length());
							} catch(IOException ioExc) {
								ioExc.printStackTrace(err);
								unixFiles[c]=null;
								relativePaths[c]=null;
							}
						}
					}
				}

				totalUniquePaths++;

				long timeSince = System.currentTimeMillis()-lastDisplayTime;
				if(timeSince<0 || timeSince>=DISPLAY_INTERVAL) {
					printStats(out, lowestRelativePath, totalObjects, totalUniquePaths, totalDirectories, totalFiles, totalSymlinks, newHardLinkCount, totalNumberFileBytes, bytesSaved);
					lastDisplayTime=System.currentTimeMillis();
				}
			}
		}

		// Print the final statistics
		printStats(out, null, totalObjects, totalUniquePaths, totalDirectories, totalFiles, totalSymlinks, newHardLinkCount, totalNumberFileBytes, bytesSaved);
	}

	private static class LinkKey {
		private final long fromDevice;
		private final long fromInode;
		private final long toDevice;
		private final long toInode;
		private final int hashCode;

		private LinkKey(long fromDevice, long fromInode, long toDevice, long toInode) {
			this.fromDevice=fromDevice;
			this.fromInode=fromInode;
			this.toDevice=toDevice;
			this.toInode=toInode;
			this.hashCode = (int)(fromDevice + fromInode*7 + toDevice*17 + toInode*37);
		}

		@Override
		public int hashCode() {
			return hashCode;
		}

		@Override
		public boolean equals(Object obj) {
			if(!(obj instanceof LinkKey)) return false;
			LinkKey other=(LinkKey)obj;
			return
				fromDevice==other.fromDevice
				&& fromInode==other.fromInode
				&& toDevice==other.toDevice
				&& toInode==other.toInode
			;
		}
	}

	private static void printStats(
		PrintStream out,
		String lastPath,
		long totalObjects,
		long totalUniquePaths,
		long totalDirectories,
		long totalFiles,
		long totalSymlinks,
		long newHardLinkCount,
		long totalNumberFileBytes,
		long bytesSaved
	) {
		out.println();
		if(lastPath!=null) {
			out.print("Current path............: "); out.println(lastPath);
		}
		out.print("Total Unique Paths......: "); out.println(totalUniquePaths);
		out.print("Total Objects...........: "); out.println(totalObjects);
		out.print("Total Directories.......: "); out.println(totalDirectories);
		out.print("Total Regular Files.....: "); out.println(totalFiles);
		out.print("Total Symbolic Links....: "); out.println(totalSymlinks);
		out.print("Total Regular File Bytes: "); out.println(Strings.getApproximateSize(totalNumberFileBytes));
		out.print("New Hard Link Count.....: "); out.println(newHardLinkCount);
		out.print("Bytes Saved by New Links: "); out.println(Strings.getApproximateSize(bytesSaved));
		out.println();
	}
}
