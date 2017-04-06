/*
 * Copyright 2001-2013, 2014, 2015, 2016, 2017 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.unix.linux;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.FTPGuestUser;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.LinuxAccountType;
import com.aoindustries.aoserv.client.LinuxGroup;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.LinuxServerGroup;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.Shell;
import com.aoindustries.aoserv.client.validator.Gecos;
import com.aoindustries.aoserv.client.validator.GroupId;
import com.aoindustries.aoserv.client.validator.LinuxId;
import com.aoindustries.aoserv.client.validator.UnixPath;
import com.aoindustries.aoserv.client.validator.UserId;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.backup.BackupManager;
import com.aoindustries.aoserv.daemon.client.AOServDaemonProtocol;
import com.aoindustries.aoserv.daemon.email.ImapManager;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.unix.ShadowFile;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.CompressedDataInputStream;
import com.aoindustries.io.CompressedDataOutputStream;
import com.aoindustries.io.FileUtils;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.util.BufferManager;
import com.aoindustries.util.ErrorPrinter;
import com.aoindustries.util.IntList;
import com.aoindustries.util.SortedArrayList;
import com.aoindustries.util.SortedIntArrayList;
import com.aoindustries.validation.ValidationException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * @author  AO Industries, Inc.
 */
public class LinuxAccountManager extends BuilderThread {

	public static final File
		newPasswd = new File("/etc/passwd.new"),
		passwd=new File("/etc/passwd"),
		backupPasswd=new File("/etc/passwd.old"),

		newGroup=new File("/etc/group.new"),
		group=new File("/etc/group"),
		backupGroup=new File("/etc/group.old"),

		newGShadow=new File("/etc/gshadow.new"),
		gshadow=new File("/etc/gshadow"),
		backupGShadow=new File("/etc/gshadow.old")
	;

	public static final String BASHRC=".bashrc";

	public static final File cronDirectory=new File("/var/spool/cron");

	/**
	 * LinuxAccounts constructor comment.
	 */
	private LinuxAccountManager() {
	}

	public static boolean comparePassword(UserId username, String password) throws IOException, SQLException {
		String crypted=ShadowFile.getEncryptedPassword(username);
		if(crypted.equals(LinuxAccount.NO_PASSWORD_CONFIG_VALUE)) return false;
		int len=crypted.length();
		if(len<2) return false;
		String salt=crypted.substring(0,2);
		if(salt.equals("$1")) {
			// MD5 crypted, use longer salt
			int pos=crypted.indexOf('$', 3);
			if(pos==-1) return false;
			salt=crypted.substring(0, pos);
		}
		String newCrypted=UnixFile.crypt(password, salt);
		return crypted.equals(newCrypted);
	}

	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			rebuildLinuxAccountSettings();
			return true;
		} catch(ThreadDeath TD) {
			throw TD;
		} catch(Throwable T) {
			LogFactory.getLogger(LinuxAccountManager.class).log(Level.SEVERE, null, T);
			return false;
		}
	}

	private static void rebuildLinuxAccountSettings() throws IOException, SQLException {
		AOServConnector connector=AOServDaemon.getConnector();
		AOServer thisAoServer = AOServDaemon.getThisAOServer();
		HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
		final UnixPath wwwDirectory = osConfig.getHttpdSitesDirectory();

		OperatingSystemVersion osv = thisAoServer.getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		if(
			osvId != OperatingSystemVersion.MANDRIVA_2006_0_I586
			&& osvId != OperatingSystemVersion.REDHAT_ES_4_X86_64
			&& osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
		) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

		int uid_min = thisAoServer.getUidMin().getId();
		int gid_min = thisAoServer.getGidMin().getId();

		synchronized(rebuildLock) {
			// A list of all files to delete is created so that all the data can
			// be backed-up before removal.
			List<File> deleteFileList=new ArrayList<>();

			// Get the list of users from the database
			List<LinuxServerAccount> accounts = thisAoServer.getLinuxServerAccounts();

			// Install /usr/bin/ftppasswd and /usr/bin/ftponly if required by any LinuxServerAccount
			boolean hasFtpShell = false;
			for(LinuxServerAccount lsa : accounts) {
				UnixPath shellPath = lsa.getLinuxAccount().getShell().getPath();
				if(
					shellPath.equals(Shell.FTPONLY)
					|| shellPath.equals(Shell.FTPPASSWD)
				) {
					hasFtpShell = true;
					break;
				}
			}
			if(hasFtpShell) {
				PackageManager.installPackage(PackageManager.PackageName.AOSERV_FTP_SHELLS);
			}

			// Add /usr/bin/passwd to /etc/shells if required by any LinuxServerAccount
			boolean hasPasswdShell = false;
			for(LinuxServerAccount lsa : accounts) {
				if(lsa.getLinuxAccount().getShell().getPath().equals(Shell.PASSWD)) {
					hasPasswdShell = true;
					break;
				}
			}
			if(hasPasswdShell) {
				PackageManager.installPackage(PackageManager.PackageName.AOSERV_PASSWD_SHELL);
			}

			// Build a sorted vector of all the usernames, user ids, and home directories
			int accountsLen = accounts.size();
			final List<String> usernames=new SortedArrayList<>(accountsLen);
			final IntList uids=new SortedIntArrayList(accountsLen);
			final List<UnixPath> homeDirs=new SortedArrayList<>(accountsLen);
			for(int c=0;c<accountsLen;c++) {
				LinuxServerAccount lsa=accounts.get(c);
				usernames.add(lsa.getLinuxAccount().getUsername().getUsername().toString());
				// UIDs are not always unique, only need to store once in the list
				int uid = lsa.getUid().getId();
				if(!uids.contains(uid)) uids.add(uid);
				// Home directories are not always unique, only need to store once in the list
				UnixPath home = lsa.getHome();
				if(!homeDirs.contains(home)) homeDirs.add(home);
			}

			/*
			 * Write the new /etc/passwd file.
			 */
			ChainWriter out = new ChainWriter(
				new BufferedOutputStream(
					new UnixFile(newPasswd).getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, true, uid_min, gid_min)
				)
			);
			try {
				// Write root first
				boolean rootFound=false;
				for (int c = 0; c < accountsLen; c++) {
					LinuxServerAccount account = accounts.get(c);
					if(account.getLinuxAccount().getUsername().getUsername().equals(LinuxAccount.ROOT)) {
						printPasswdLine(account, out);
						rootFound=true;
						break;
					}
				}
				if(!rootFound) throw new SQLException("root user not found while creating "+newPasswd.getPath());
				for (int c = 0; c < accountsLen; c++) {
					LinuxServerAccount account = accounts.get(c);
					if(!account.getLinuxAccount().getUsername().getUsername().equals(LinuxAccount.ROOT)) {
						printPasswdLine(account, out);
					}
				}
			} finally {
				out.flush();
				out.close();
			}

			/*
			 * Write the new /etc/group file.
			 */
			List<LinuxServerGroup> groups = thisAoServer.getLinuxServerGroups();
			int groupsLen = groups.size();
			out = new ChainWriter(
				new BufferedOutputStream(
					new UnixFile(newGroup).getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, true, uid_min, gid_min)
				)
			);
			try {
				Map<UserId,Object> tempMap=new HashMap<>();
				boolean rootFound=false;
				// Write root first
				for (int c = 0; c < groupsLen; c++) {
					LinuxServerGroup lsg=groups.get(c);
					if(lsg.getLinuxGroup().getName().equals(LinuxGroup.ROOT)) {
						printGroupLine(groups.get(c), out, true, tempMap);
						rootFound=true;
						break;
					}
				}
				if(!rootFound) throw new SQLException("root group not found while creating "+newGroup.getPath());
				for (int c = 0; c < groupsLen; c++) {
					LinuxServerGroup lsg=groups.get(c);
					if(!lsg.getLinuxGroup().getName().equals(LinuxGroup.ROOT)) {
						printGroupLine(groups.get(c), out, true, tempMap);
					}
				}
			} finally {
				out.flush();
				out.close();
			}

			/*
			 * Write the new /etc/gshadow file.
			 */
			UnixFile newGShadowUF = new UnixFile(newGShadow);
			out = new ChainWriter(
				new BufferedOutputStream(
					newGShadowUF.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0600, true, uid_min, gid_min)
				)
			);
			try {
				// Write root first
				boolean rootFound=false;
				for (int c = 0; c < groupsLen; c++) {
					LinuxServerGroup lsg=groups.get(c);
					if(lsg.getLinuxGroup().getName().equals(LinuxGroup.ROOT)) {
						printGShadowLine(groups.get(c), out);
						rootFound=true;
						break;
					}
				}
				if(!rootFound) throw new SQLException("root group not found while creating "+newGShadow.getPath());
				for (int c = 0; c < groupsLen; c++) {
					LinuxServerGroup lsg=groups.get(c);
					if(!lsg.getLinuxGroup().getName().equals(LinuxGroup.ROOT)) {
						printGShadowLine(groups.get(c), out);
					}
				}
			} finally {
				out.flush();
				out.close();
			}

			ShadowFile.rebuildShadowFile(accounts);

			/*
			 * Move the new files into place.
			 */
			if(newPasswd.length()>0) {
				FileUtils.rename(passwd, backupPasswd);
				FileUtils.rename(newPasswd, passwd);
			} else throw new IOException(newPasswd.getPath()+" is zero or unknown length");

			if(newGroup.length()>0) {
				FileUtils.rename(group, backupGroup);
				FileUtils.rename(newGroup, group);
			} else throw new IOException(newGroup.getPath()+" is zero or unknown length");

			if(newGShadow.length()>0) {
				if(osvId==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
					// Do nothing
				} else if(osvId==OperatingSystemVersion.REDHAT_ES_4_X86_64) {
					// Do nothing
				} else if(osvId==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
					newGShadowUF.setMode(0400);
				} else throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
				FileUtils.rename(gshadow, backupGShadow);
				FileUtils.rename(newGShadow, gshadow);
			} else throw new IOException(newGShadow.getPath()+" is zero or unknown length");

			if(
				osvId==OperatingSystemVersion.MANDRIVA_2006_0_I586
			) {
				/*
				 * Create any inboxes that need to exist.
				 */
				LinuxServerGroup mailGroup=connector.getLinuxGroups().get(LinuxGroup.MAIL).getLinuxServerGroup(thisAoServer);
				if(mailGroup==null) throw new SQLException("Unable to find LinuxServerGroup: "+LinuxGroup.MAIL+" on "+thisAoServer.getHostname());
				for (LinuxServerAccount account : accounts) {
					LinuxAccount linuxAccount = account.getLinuxAccount();
					if(linuxAccount.getType().isEmail()) {
						UserId username=linuxAccount.getUsername().getUsername();
						File file=new File(ImapManager.mailSpool, username.toString());
						if(!file.exists()) {
							UnixFile unixFile=new UnixFile(file.getPath());
							unixFile.getSecureOutputStream(
								account.getUid().getId(),
								mailGroup.getGid().getId(),
								0660,
								false,
								uid_min,
								gid_min
							).close();
						}
					}
				}
				/*
				 * Remove any inboxes that should not exist.
				 */
				String[] list=ImapManager.mailSpool.list();
				if(list!=null) {
					int len=list.length;
					for(int c=0;c<len;c++) {
						String filename=list[c];
						if(!usernames.contains(filename)) {
							// Also allow a username.lock file to remain
							if(
								!filename.endsWith(".lock")
								|| !usernames.contains(filename.substring(0, filename.length()-5))
							) {
								File spoolFile=new File(ImapManager.mailSpool, filename);
								deleteFileList.add(spoolFile);
							}
						}
					}
				}
			} else if(
				osvId==OperatingSystemVersion.REDHAT_ES_4_X86_64
				|| osvId==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
			) {
				// Nothing done, user management put in ImapManager
			} else {
				throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
			}

			/*
			 * Create any home directories that do not exist.
			 */
			for (int c = 0; c < accountsLen; c++) {
				LinuxServerAccount account = accounts.get(c);
				LinuxAccount linuxAccount=account.getLinuxAccount();
				String type=linuxAccount.getType().getName();
				//String username=linuxAccount.getUsername().getUsername();
				LinuxServerGroup primaryGroup=account.getPrimaryLinuxServerGroup();
				//String groupname=group.getLinuxGroup().getName();
				int uid=account.getUid().getId();
				int gid=primaryGroup.getGid().getId();

				File homeDir=new File(account.getHome().toString());
				if(!homeDir.exists()) {
					// Make the parent of the home directory if it does not exist
					File parent=homeDir.getParentFile();
					if(!parent.exists()) {
						parent.mkdir();
						UnixFile unixFile=new UnixFile(parent.getPath());
						unixFile.chown(UnixFile.ROOT_UID, UnixFile.ROOT_GID);
						unixFile.setMode(0755);
					}

					// Make the home directory
					homeDir.mkdir();
				}

				// Set up the directory if it was just created or was created as root before
				String th=homeDir.getPath();
				// Homes in /www will have all the skel copied, but will not set the directory perms
				boolean isWWWAndUser =
					th.startsWith(wwwDirectory.toString() + '/')
					&& (type.equals(LinuxAccountType.USER) || type.equals(LinuxAccountType.APPLICATION))
					&& linuxAccount.getFTPGuestUser()==null
				;
				// Only build directories for accounts that are in /home/ or user account in /www/
				if(
					isWWWAndUser
					|| (th.length()>6 && th.substring(0, 6).equals("/home/"))
				) {
					UnixFile unixFile=new UnixFile(th);
					Stat unixFileStat = unixFile.getStat();
					if(
						!isWWWAndUser
						&& (
							unixFileStat.getUid()==UnixFile.ROOT_UID
							|| unixFileStat.getGid()==UnixFile.ROOT_GID
						)
						// Do not set permissions for encrypted home directories
						&& !(new UnixFile(th+".aes256.img").getStat().exists())
					) {
						unixFile.chown(uid, gid);
						unixFile.setMode(0700);
					}

					// Copy the /etc/skel directory
					File skel=new File("/etc/skel");
					String[] skelList=skel.list();
					if(skelList!=null) {
						int len=skelList.length;
						for(int d=0;d<len;d++) {
							// Copy the file
							String filename=skelList[d];
							// Only copy the files for user accounts
							if(type.equals(LinuxAccountType.USER) || type.equals(LinuxAccountType.APPLICATION)) {
								// Only do the rest of the files for user accounts
								UnixFile skelFile=new UnixFile(skel, filename);
								UnixFile homeFile=new UnixFile(homeDir, filename);
								if(!homeFile.getStat().exists()) {
									skelFile.copyTo(homeFile, false);
									homeFile.chown(uid, gid);
								}
							}
						}
					}
				}
			}

			/*
			 * Remove any home directories that should not exist.
			 */
			for(char ch='a';ch<='z';ch++) {
				File homeDir=new File("/home/"+ch);
				String[] homeList=homeDir.list();
				if(homeList!=null) {
					int len=homeList.length;
					for(int c=0;c<len;c++) {
						String dirName=homeList[c];
						File dir=new File(homeDir, dirName);
						String dirPath=dir.getPath();
						// Allow encrypted form of home directory
						if(dirPath.endsWith(".aes256.img")) dirPath = dirPath.substring(0, dirPath.length()-11);
						try {
							if(!homeDirs.contains(UnixPath.valueOf(dirPath))) deleteFileList.add(dir);
						} catch(ValidationException e) {
							throw new IOException(e);
						}
					}
				}
			}

			/*
			 * Remove any cron jobs that should not exist.
			 */ 
			String[] cronList=cronDirectory.list();
			if(cronList!=null) {
				int len=cronList.length;
				for(int c=0;c<len;c++) {
					String filename=cronList[c];

					// Filename must be the username of one of the users to be kept in tact
					if(!usernames.contains(filename)) deleteFileList.add(new File(cronDirectory, filename));
				}
			}

			// Disable and enable accounts
			for (LinuxServerAccount lsa : accounts) {
				String prePassword=lsa.getPredisablePassword();
				if(!lsa.isDisabled()) {
					if(prePassword!=null) {
						setEncryptedPassword(lsa.getLinuxAccount().getUsername().getUsername(), prePassword);
						lsa.setPredisablePassword(null);
					}
				} else {
					if(prePassword==null) {
						UserId username=lsa.getLinuxAccount().getUsername().getUsername();
						lsa.setPredisablePassword(getEncryptedPassword(username));
						setPassword(username, null);
					}
				}
			}

			// Only the top level server in a physical server gets to kill processes
			if(!AOServDaemonConfiguration.isNested() && thisAoServer.getFailoverServer()==null) {
				List<AOServer> nestedServers=thisAoServer.getNestedAOServers();

				/*
				 * Kill any processes that are running as a UID that
				 * should not exist on this server.
				 */
				File procDir=new File("/proc");
				String[] procList=procDir.list();
				if(procList!=null) {
					int len=procList.length;
					for(int c=0;c<len;c++) {
						String filename=procList[c];
						int flen=filename.length();
						boolean allNum=true;
						for(int d=0;d<flen;d++) {
							char ch=filename.charAt(d);
							if(ch<'0' || ch>'9') {
								allNum=false;
								break;
							}
						}
						if(allNum) {
							try {
								LinuxProcess process=new LinuxProcess(Integer.parseInt(filename));
								int uid=process.getUid();
								// Don't kill root processes, just to be safe
								if(uid!=0) {
									// Search each server
									LinuxServerAccount lsa;
									if(
										!uids.contains(uid)
										|| (lsa=thisAoServer.getLinuxServerAccount(LinuxId.valueOf(uid))) == null
										|| lsa.isDisabled()
									) {
										// Also must not be in a nested server
										boolean found=false;
										for (AOServer nestedServer : nestedServers) {
											lsa = nestedServer.getLinuxServerAccount(LinuxId.valueOf(uid));
											if(
												lsa!=null
												&& !lsa.isDisabled()
											) {
												found=true;
												break;
											}
										}
										if(!found) process.killProc();
									}
								}
							} catch(FileNotFoundException err) {
								// It is normal that this is thrown if the process has already closed
							} catch(IOException | ValidationException err) {
								LogFactory.getLogger(LinuxAccountManager.class).log(Level.SEVERE, "filename="+filename, err);
							}
						}
					}
				}
			}

			/*
			 * Recursively find and remove any temporary files that should not exist.
			 */
			try {
				AOServDaemon.findUnownedFiles(new File("/tmp"), uids, deleteFileList, 0);
				AOServDaemon.findUnownedFiles(new File("/var/tmp"), uids, deleteFileList, 0);
			} catch(FileNotFoundException err) {
				// This may normally occur because of the dynamic nature of the tmp directories
			}

			/*
			 * Back up the files scheduled for removal.
			 */
			int deleteFileListLen=deleteFileList.size();
			if(deleteFileListLen>0) {
				// Get the next backup filename
				File backupFile=BackupManager.getNextTarballBackupFile();
				BackupManager.createTarball(deleteFileList, backupFile);

				/*
				 * Remove the files that have been backed up.
				 */
				for(int c=0;c<deleteFileListLen;c++) {
					File file=deleteFileList.get(c);
					new UnixFile(file.getPath()).secureDeleteRecursive(uid_min, gid_min);
				}
			}

			if(!hasPasswdShell) {
				PackageManager.removePackage(PackageManager.PackageName.AOSERV_PASSWD_SHELL);
			}
			if(!hasFtpShell) {
				PackageManager.removePackage(PackageManager.PackageName.AOSERV_FTP_SHELLS);
			}
		}
	}

	public static String getAutoresponderContent(UnixPath path) throws IOException, SQLException {
		UnixFile file=new UnixFile(path.toString());
		String content;
		if(file.getStat().exists()) {
			StringBuilder SB=new StringBuilder();
			AOServer thisAoServer = AOServDaemon.getThisAOServer();
			int uid_min = thisAoServer.getUidMin().getId();
			int gid_min = thisAoServer.getGidMin().getId();
			try (InputStream in = new BufferedInputStream(file.getSecureInputStream(uid_min, gid_min))) {
				int ch;
				while((ch=in.read())!=-1) SB.append((char)ch);
			}
			content=SB.toString();
		} else content="";
		return content;
	}

	public static String getCronTable(UserId username) throws IOException, SQLException {
		File cronFile=new File(cronDirectory, username.toString());
		String cronTable;
		if(cronFile.exists()) {
			StringBuilder SB=new StringBuilder();
			try (InputStream in = new BufferedInputStream(new FileInputStream(cronFile))) {
				int ch;
				while((ch=in.read())!=-1) SB.append((char)ch);
			}
			cronTable=SB.toString();
		} else cronTable="";
		return cronTable;
	}

	public static String getEncryptedPassword(UserId username) throws IOException, SQLException {
		return ShadowFile.getEncryptedPassword(username);
	}

	/**
	 * Prints one line of a Linux group file.
	 *
	 * @param  group     the <code>LinuxServerGroup</code> to print
	 * @param  out       the <code>ChainWriter</code> to print to
	 * @param  complete  if <code>true</code>, a complete line will be printed, otherwise
	 *                   only the groupname and gid are included
	 */
	public static void printGroupLine(LinuxServerGroup group, ChainWriter out, boolean complete, Map<UserId,Object> tempMap) throws IOException, SQLException {
		LinuxGroup linuxGroup = group.getLinuxGroup();
		GroupId groupName=linuxGroup.getName();
		out
			.print(groupName)
			.print(complete?":*:":"::")
			.print(group.getGid().getId())
			.print(':')
		;
		if(complete) {
			tempMap.clear();
			List<LinuxServerAccount> altAccounts = group.getAlternateLinuxServerAccounts();
			boolean didOne=false;
			int len = altAccounts.size();
			for (int d = 0; d < len; d++) {
				UserId username = altAccounts.get(d)
					.getLinuxAccount()
					.getUsername()
					.getUsername()
				;
				if(!tempMap.containsKey(username)) {
					if(didOne) out.print(',');
					else didOne=true;
					out.print(username);
					tempMap.put(username, null);
				}
			}
			if(groupName.equals(LinuxGroup.PROFTPD_JAILED)) {
				AOServer aoServer=group.getAOServer();
				OperatingSystemVersion osv = aoServer.getServer().getOperatingSystemVersion();
				int osvId = osv.getPkey();
				boolean addJailed;
				if(
					osvId==OperatingSystemVersion.MANDRIVA_2006_0_I586
					|| osvId==OperatingSystemVersion.REDHAT_ES_4_X86_64
				) {
					addJailed = true;
				} else if(osvId==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
					addJailed = false;
				} else throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
				if(addJailed) {
					for(FTPGuestUser guestUser : aoServer.getFTPGuestUsers()) {
						UserId username=guestUser.getLinuxAccount().getUsername().getUsername();
						if(!tempMap.containsKey(username)) {
							if(didOne) out.print(',');
							else didOne=true;
							out.print(username);
							tempMap.put(username, null);
						}
					}
				}
			}
		}
		out.print('\n');
	}

	public static void printGShadowLine(LinuxServerGroup group, ChainWriter out) throws IOException, SQLException {
		LinuxGroup linuxGroup = group.getLinuxGroup();
		out.print(linuxGroup.getName()).print(":::\n");
	}

	/**
	 * Prints one line of a Linux passwd file.
	 *
	 * @param  account   the <code>LinuxServerAccount</code> to print
	 * @param  out       the <code>ChainWriter</code> to print to
	 */
	public static void printPasswdLine(LinuxServerAccount account, ChainWriter out) throws IOException, SQLException {
		LinuxAccount linuxAccount = account.getLinuxAccount();
		UserId username=linuxAccount.getUsername().getUsername();
		LinuxServerGroup primaryGroup = account.getPrimaryLinuxServerGroup();
		if(primaryGroup==null) throw new SQLException("Unable to find primary LinuxServerGroup for username="+username+" on "+account.getAOServer().getHostname());
		out
			.print(username)
			.print(":x:")
			.print(account.getUid().getId())
			.print(':')
			.print(primaryGroup.getGid().getId())
			.print(':')
		;
		int commaCount = 0;
		Gecos fullName = linuxAccount.getName();
		if(fullName != null) {
			out.print(fullName.toString());
		}
		Gecos officeLocation = linuxAccount.getOfficeLocation();
		if(officeLocation != null) {
			out.print(',');
			commaCount++;
			out.print(officeLocation.toString());
		}
		Gecos officePhone = linuxAccount.getOfficePhone();
		if(officePhone != null) {
			while(commaCount < 2) {
				out.print(',');
				commaCount++;
			}
			out.print(officePhone.toString());
		}
		Gecos homePhone = linuxAccount.getHomePhone();
		if(homePhone != null) {
			while(commaCount < 3) {
				out.print(',');
				commaCount++;
			}
			out.print(homePhone.toString());
		}
		out
			.print(':')
			.print(account.getHome())
			.print(':')
			.print(linuxAccount.getShell().getPath())
			.print('\n')
		;
	}

	public static void setBashProfile(LinuxServerAccount lsa, String profile) throws IOException, SQLException {
		String profileLine=". "+profile;

		UnixFile profileFile=new UnixFile(lsa.getHome().toString(), BASHRC);

		// Make sure the file exists
		if(profileFile.getStat().exists()) {
			AOServer thisAoServer = AOServDaemon.getThisAOServer();
			int uid_min = thisAoServer.getUidMin().getId();
			int gid_min = thisAoServer.getGidMin().getId();

			boolean found=false;
			// Read the old file, looking for the source in the file
			try (BufferedReader in=new BufferedReader(new InputStreamReader(profileFile.getSecureInputStream(uid_min, gid_min)))) {
				String line;

				while((line=in.readLine())!=null) {
					if(line.equals(profileLine)) {
						found=true;
						break;
					}
				}
			}
			if(!found) {
				try (RandomAccessFile out=profileFile.getSecureRandomAccessFile("rw", uid_min, gid_min)) {
					out.seek(out.length());
					out.seek(out.length());
					out.write('\n');
					out.writeBytes(profileLine);
					out.write('\n');
				}
			}
		}
	}

	public static void setAutoresponderContent(UnixPath path, String content, int uid, int gid) throws IOException, SQLException {
		AOServer thisAoServer = AOServDaemon.getThisAOServer();
		int uid_min = thisAoServer.getUidMin().getId();
		int gid_min = thisAoServer.getGidMin().getId();
		File file=new File(path.toString());
		synchronized(rebuildLock) {
			if(content==null) {
				if(file.exists()) FileUtils.delete(file);
			} else {
				try (
					PrintWriter out = new PrintWriter(
						new BufferedOutputStream(
							new UnixFile(file).getSecureOutputStream(uid, gid, 0600, true, uid_min, gid_min)
						)
					)
				) {
					out.print(content);
				}
			}
		}
	}

	public static void setCronTable(UserId username, String cronTable) throws IOException, SQLException {
		AOServer thisAoServer = AOServDaemon.getThisAOServer();
		int uid_min = thisAoServer.getUidMin().getId();
		int gid_min = thisAoServer.getGidMin().getId();
		File cronFile=new File(cronDirectory, username.toString());
		synchronized(rebuildLock) {
			if(cronTable.isEmpty()) {
				if(cronFile.exists()) FileUtils.delete(cronFile);
			} else {
				try (
					PrintWriter out = new PrintWriter(
						new BufferedOutputStream(
							new UnixFile(cronFile).getSecureOutputStream(
								UnixFile.ROOT_UID,
								thisAoServer.getLinuxServerAccount(username).getPrimaryLinuxServerGroup().getGid().getId(),
								0600,
								true,
								uid_min,
								gid_min
							)
						)
					)
				) {
					out.print(cronTable);
				}
			}
		}
	}

	public static void setEncryptedPassword(UserId username, String encryptedPassword) throws IOException, SQLException {
		AOServer aoServer=AOServDaemon.getThisAOServer();
		LinuxServerAccount lsa=aoServer.getLinuxServerAccount(username);
		if(lsa==null) throw new SQLException("Unable to find LinuxServerAccount: "+username+" on "+aoServer.getHostname());
		ShadowFile.setEncryptedPassword(username, encryptedPassword);
	}

	public static void setPassword(UserId username, String plain_password) throws IOException, SQLException {
		AOServer aoServer=AOServDaemon.getThisAOServer();
		LinuxServerAccount lsa=aoServer.getLinuxServerAccount(username);
		if(lsa==null) throw new SQLException("Unable to find LinuxServerAccount: "+username+" on "+aoServer.getHostname());
		ShadowFile.setPassword(username, plain_password);
	}

	private static LinuxAccountManager linuxAccountManager;
	public static void start() throws IOException, SQLException {
		AOServer thisAOServer = AOServDaemon.getThisAOServer();
		OperatingSystemVersion osv = thisAOServer.getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();

		synchronized(System.out) {
			if(
				// Nothing is done for these operating systems
				osvId != OperatingSystemVersion.CENTOS_5_DOM0_I686
				&& osvId != OperatingSystemVersion.CENTOS_5_DOM0_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_DOM0_X86_64
				// Check config after OS check so config entry not needed
				&& AOServDaemonConfiguration.isManagerEnabled(LinuxAccountManager.class)
				&& linuxAccountManager == null
			) {
				System.out.print("Starting LinuxAccountManager: ");
				// Must be a supported operating system
				if(
					osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586
					|| osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				) {
					AOServConnector conn = AOServDaemon.getConnector();
					linuxAccountManager = new LinuxAccountManager();
					conn.getFtpGuestUsers().addTableListener(linuxAccountManager, 0);
					conn.getLinuxAccounts().addTableListener(linuxAccountManager, 0);
					conn.getLinuxGroupAccounts().addTableListener(linuxAccountManager, 0);
					conn.getLinuxServerAccounts().addTableListener(linuxAccountManager, 0);
					conn.getLinuxServerGroups().addTableListener(linuxAccountManager, 0);
					System.out.println("Done");
				} else {
					System.out.println("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}

	public static void tarHomeDirectory(CompressedDataOutputStream out, UserId username) throws IOException, SQLException {
		LinuxServerAccount lsa=AOServDaemon.getThisAOServer().getLinuxServerAccount(username);
		UnixPath home=lsa.getHome();
		UnixFile tempUF=UnixFile.mktemp("/tmp/tar_home_directory.tar.", true);
		try {
			AOServDaemon.exec(
				"/bin/tar",
				"-c",
				"-C",
				home.toString(),
				"-f",
				tempUF.getPath(),
				"."
			);
			try (InputStream in=new FileInputStream(tempUF.getFile())) {
				byte[] buff=BufferManager.getBytes();
				try {
					int ret;
					while((ret=in.read(buff, 0, BufferManager.BUFFER_SIZE))!=-1) {
						out.writeByte(AOServDaemonProtocol.NEXT);
						out.writeShort(ret);
						out.write(buff, 0, ret);
					}
				} finally {
					BufferManager.release(buff, false);
				}
			}
		} finally {
			tempUF.delete();
		}
	}

	public static void untarHomeDirectory(CompressedDataInputStream in, UserId username) throws IOException, SQLException {
		AOServer thisAoServer = AOServDaemon.getThisAOServer();
		int uid_min = thisAoServer.getUidMin().getId();
		int gid_min = thisAoServer.getGidMin().getId();
		synchronized(rebuildLock) {
			LinuxServerAccount lsa=thisAoServer.getLinuxServerAccount(username);
			UnixPath home=lsa.getHome();
			UnixFile tempUF=UnixFile.mktemp("/tmp/untar_home_directory.tar.", true);
			try {
				int code;
				try (OutputStream out=tempUF.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0600, true, uid_min, gid_min)) {
					byte[] buff=BufferManager.getBytes();
					try {
						while((code=in.readByte())==AOServDaemonProtocol.NEXT) {
							int len=in.readShort();
							in.readFully(buff, 0, len);
							out.write(buff, 0, len);
						}
					} finally {
						BufferManager.release(buff, false);
					}
				}
				if(code!=AOServDaemonProtocol.DONE) {
					if(code==AOServDaemonProtocol.IO_EXCEPTION) throw new IOException(in.readUTF());
					else if(code==AOServDaemonProtocol.SQL_EXCEPTION) throw new SQLException(in.readUTF());
					else throw new IOException("Unknown result: " + code);
				}
				AOServDaemon.exec(
					"/bin/tar",
					"-x",
					"-C",
					home.toString(),
					"-f",
					tempUF.getPath()
				);
			} finally {
				tempUF.delete();
			}
		}
	}

	public static void waitForRebuild() {
		if(linuxAccountManager!=null) linuxAccountManager.waitForBuild();
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild Linux Accounts";
	}

	@Override
	public long getProcessTimerMaximumTime() {
		return 15*60*1000;
	}

	/**
	 * Allows manual rebuild without the necessity of running the entire daemon (use carefully, only when main daemon not running).
	 */
	public static void main(String[] args) {
		try {
			rebuildLinuxAccountSettings();
		} catch(RuntimeException | IOException | SQLException err) {
			ErrorPrinter.printStackTraces(err);
		}
	}
}
