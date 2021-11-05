/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2002-2013, 2015, 2016, 2017, 2018, 2019, 2020, 2021  AO Industries, Inc.
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
package com.aoindustries.aoserv.daemon.cvsd;

import com.aoapps.collections.AoCollections;
import com.aoapps.io.posix.PosixFile;
import com.aoapps.io.posix.Stat;
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.linux.PosixPath;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.linux.UserServer;
import com.aoindustries.aoserv.client.scm.CvsRepository;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.backup.BackupManager;
import com.aoindustries.aoserv.daemon.posix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handles the building of CVS repositories, xinetd configs are handled by XinetdManager.
 *
 * @author  AO Industries, Inc.
 */
public final class CvsManager extends BuilderThread {

	private static final Logger logger = Logger.getLogger(CvsManager.class.getName());

	private static CvsManager cvsManager;

	private CvsManager() {
		// Do nothing
	}

	private static final Object rebuildLock = new Object();
	@Override
	@SuppressWarnings({"UseSpecificCatch", "TooBroadCatch"})
	protected boolean doRebuild() {
		try {
			Server thisServer = AOServDaemon.getThisServer();
			OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(
				osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_X86_64
			) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

			synchronized(rebuildLock) {
				List<CvsRepository> cvsRepositories = thisServer.getCvsRepositories();
				boolean cvsInstalled;
				// Install RPM when at least one CVS repository is configured
				if(
					!cvsRepositories.isEmpty()
					&& (
						osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
						|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
					)
				) {
					// Install any required RPMs
					PackageManager.installPackage(PackageManager.PackageName.CVS);
					cvsInstalled = true;
				} else {
					cvsInstalled = PackageManager.getInstalledPackage(PackageManager.PackageName.CVS) != null;
				}
				File cvsDir = new File(CvsRepository.DEFAULT_CVS_DIRECTORY.toString());
				// Create /var/cvs if missing
				if(
					(cvsInstalled || !cvsRepositories.isEmpty())
					&& !cvsDir.exists()
				) new PosixFile(cvsDir).mkdir(false, 0755, PosixFile.ROOT_UID, PosixFile.ROOT_GID);
				// Get a list of all the directories in /var/cvs
				Set<String> existing;
				{
					String[] list = cvsDir.list();
					if(list != null) {
						int listLen = list.length;
						existing = AoCollections.newHashSet(listLen);
						for(int c = 0; c < listLen; c++) {
							existing.add(CvsRepository.DEFAULT_CVS_DIRECTORY + "/" + list[c]);
						}
					} else {
						existing = new HashSet<>();
					}
				}

				// Add each directory that doesn't exist, fix permissions and ownerships, too
				// while removing existing directories from existing
				for(CvsRepository cvs : cvsRepositories) {
					PosixPath path = cvs.getPath();
					PosixFile cvsUF = new PosixFile(path.toString());
					UserServer lsa = cvs.getLinuxServerAccount();
					{
						Stat cvsStat = cvsUF.getStat();
						long cvsMode = cvs.getMode();
						// Make the directory
						if(!cvsStat.exists()) {
							cvsUF.mkdir(true, cvsMode);
							cvsStat = cvsUF.getStat();
						}
						// Set the mode
						if(cvsStat.getMode() != cvsMode) {
							cvsUF.setMode(cvsMode);
							cvsStat = cvsUF.getStat();
						}
						// Set the owner and group
						int uid = lsa.getUid().getId();
						int gid = cvs.getLinuxServerGroup().getGid().getId();
						if(uid != cvsStat.getUid() || gid != cvsStat.getGid()) {
							cvsUF.chown(uid, gid);
							// Unused here, no need to re-stat: cvsStat = cvsUF.getStat();
						}
					}
					// Init if needed
					PosixFile cvsRootUF = new PosixFile(cvsUF, "CVSROOT", false);
					if(!cvsRootUF.getStat().exists()) {
						AOServDaemon.suexec(
							lsa.getLinuxAccount_username_id(),
							new File(lsa.getHome().toString()),
							"/usr/bin/cvs -d " + path + " init",
							0
						);
					}
					// Remove from list
					existing.remove(path.toString());
				}

				// Back-up and delete the files scheduled for removal.
				if(!existing.isEmpty()) {
					List<File> deleteFileList = new ArrayList<>(existing.size());
					for(String deleteFilename : existing) {
						deleteFileList.add(new File(deleteFilename));
					}
					BackupManager.backupAndDeleteFiles(deleteFileList);
				}
				// Remove /var/cvs if empty
				if(!cvsInstalled && cvsDir.exists()) {
					String[] list = cvsDir.list();
					if(list == null || list.length == 0) Files.delete(cvsDir.toPath());
				}
			}
			return true;
		} catch(ThreadDeath td) {
			throw td;
		} catch(Throwable t) {
			logger.log(Level.SEVERE, null, t);
			return false;
		}
	}

	@SuppressWarnings("UseOfSystemOutOrSystemErr")
	public static void start() throws IOException, SQLException {
		Server thisServer = AOServDaemon.getThisServer();
		OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
		int osvId = osv.getPkey();

		synchronized(System.out) {
			if(
				// Nothing is done for these operating systems
				osvId != OperatingSystemVersion.CENTOS_5_DOM0_I686
				&& osvId != OperatingSystemVersion.CENTOS_5_DOM0_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_DOM0_X86_64
				// Check config after OS check so config entry not needed
				&& AOServDaemonConfiguration.isManagerEnabled(CvsManager.class)
				&& cvsManager == null
			) {
				System.out.print("Starting CvsManager: ");
				// Must be a supported operating system
				if(
					osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
				) {
					AOServConnector conn = AOServDaemon.getConnector();
					cvsManager = new CvsManager();
					conn.getScm().getCvsRepository().addTableListener(cvsManager, 0);
					conn.getLinux().getUserServer().addTableListener(cvsManager, 0);
					conn.getLinux().getGroupServer().addTableListener(cvsManager, 0);
					PackageManager.addPackageListener(cvsManager);
					System.out.println("Done");
				} else {
					System.out.println("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild CVS";
	}
}
