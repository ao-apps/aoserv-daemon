/*
 * aoserv-daemon - Server management daemon for the AOServ Platform.
 * Copyright (C) 2002-2013, 2014, 2016, 2017, 2018, 2019, 2020  AO Industries, Inc.
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
package com.aoindustries.aoserv.daemon.server;

import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.lang.EmptyArrays;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * The <code>ServerManager</code> controls stuff at a server level.
 *
 * @author  AO Industries, Inc.
 */
final public class ServerManager {

	private ServerManager() {
	}

	private static final File procLoadavg = new File("/proc/loadavg");
	private static final File procMeminfo = new File("/proc/meminfo");
	private static final File xenAutoStartDirectory = new File("/etc/xen/auto");

	/** One lock per process name */
	private static final Map<String,Object> processLocks=new HashMap<>();
	public static void controlProcess(String process, String command) throws IOException, SQLException {
		OperatingSystemVersion osv = AOServDaemon.getThisServer().getHost().getOperatingSystemVersion();
		int osvId = osv.getPkey();

		Object lock;
		synchronized(processLocks) {
			lock=processLocks.get(process);
			if(lock==null) processLocks.put(process, lock=new Object());
		}
		synchronized(lock) {
			if(
				osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586
				|| osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64
				|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
			) {
				AOServDaemon.exec(
					"/etc/rc.d/init.d/" + process,
					command
				);
			} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
				AOServDaemon.exec("/usr/bin/systemctl", command, process + ".service");
			} else {
				throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
			}
		}
	}

	public static void restartCron() throws IOException, SQLException {
		controlProcess("crond", "restart");
	}

	public static void restartXfs() throws IOException, SQLException {
		controlProcess("xfs", "restart");
	}

	public static void restartXvfb() throws IOException, SQLException {
		controlProcess("xvfb", "restart");
	}

	public static void startCron() throws IOException, SQLException {
		controlProcess("crond", "start");
	}

	public static void startXfs() throws IOException, SQLException {
		controlProcess("xfs", "start");
	}

	public static void startXvfb() throws IOException, SQLException {
		controlProcess("xvfb", "start");
	}

	public static void stopCron() throws IOException, SQLException {
		controlProcess("crond", "stop");
	}

	public static void stopXfs() throws IOException, SQLException {
		controlProcess("xfs", "stop");
	}

	public static void stopXvfb() throws IOException, SQLException {
		controlProcess("xvfb", "stop");
	}

	public static String get3wareRaidReport() throws IOException {
		return AOServDaemon.execAndCapture(
			"/opt/tw_cli/tw_cli",
			"show"
		);
	}

	public static String getMdStatReport() throws IOException {
		File procFile = new File("/proc/mdstat");
		String report;
		if(procFile.exists()) {
			StringBuilder SB=new StringBuilder();
			try (InputStream in = new BufferedInputStream(new FileInputStream(procFile))) {
				int ch;
				while((ch=in.read())!=-1) SB.append((char)ch);
			}
			report = SB.toString();
		} else report="";
		return report;
	}

	public static String getMdMismatchReport() throws IOException {
		return AOServDaemon.execAndCapture(
			"/opt/aoserv-daemon/bin/get_md_mismatch"
		);
	}

	public static String getDrbdReport() throws IOException {
		// Make sure perl is installed as required by drbdcstate
		PackageManager.installPackage(PackageManager.PackageName.PERL);
		return AOServDaemon.execAndCapture(
			"/opt/aoserv-daemon/bin/drbdcstate"
		);
	}

	public static String[] getLvmReport() throws IOException {
		return new String[] {
			AOServDaemon.execAndCapture(
				"/usr/sbin/vgs",
				"--noheadings",
				"--separator=\t",
				"--units=b",
				"-o",
				"vg_name,vg_extent_size,vg_extent_count,vg_free_count,pv_count,lv_count"
			),
			AOServDaemon.execAndCapture(
				"/usr/sbin/pvs",
				"--noheadings",
				"--separator=\t",
				"--units=b",
				"-o",
				"pv_name,pv_pe_count,pv_pe_alloc_count,pv_size,vg_name"
			),
			AOServDaemon.execAndCapture(
				"/usr/sbin/lvs",
				"--noheadings",
				"--separator=\t",
				"-o",
				"vg_name,lv_name,seg_count,segtype,stripes,seg_start_pe,seg_pe_ranges"
			)
		};
	}

	public static String getHddTempReport() throws IOException {
		PackageManager.installPackages(
			// Make sure /usr/sbin/hddtemp is installed as required by hddtemp
			PackageManager.PackageName.HDDTEMP
			// Make sure /usr/sbin/smartctl is installed as required by hddtemp
			// No longer needed since no more 3ware support: PackageManager.PackageName.SMARTMONTOOLS
		);
		return AOServDaemon.execAndCapture(
			"/opt/aoserv-daemon/bin/aoserv-hddtemp"
		);
	}

	public static String getHddModelReport() throws IOException {
		return AOServDaemon.execAndCapture(
			"/opt/aoserv-daemon/bin/hddmodel"
		);
	}

	public static String getFilesystemsCsvReport() throws IOException, SQLException {
		OperatingSystemVersion osv = AOServDaemon.getThisServer().getHost().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		if(
			osvId == OperatingSystemVersion.REDHAT_ES_4_X86_64
			|| osvId == OperatingSystemVersion.CENTOS_5_DOM0_I686
			|| osvId == OperatingSystemVersion.CENTOS_5_DOM0_X86_64
			|| osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
			|| osvId == OperatingSystemVersion.CENTOS_7_DOM0_X86_64
			|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
		) {
			// Make sure perl is installed as required by filesystemscsv
			PackageManager.installPackage(PackageManager.PackageName.PERL);
			return AOServDaemon.execAndCapture(
				"/opt/aoserv-daemon/bin/filesystemscsv"
			);
		} else if(osvId == OperatingSystemVersion.MANDRIVA_2006_0_I586) {
			return AOServDaemon.execAndCapture(
				"/usr/aoserv/daemon/bin/filesystemscsv"
			);
		} else {
			throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
		}
	}

	public static String getLoadAvgReport() throws IOException, SQLException {
		StringBuilder report = new StringBuilder(40);
		try (InputStream in = new BufferedInputStream(new FileInputStream(procLoadavg))) {
			int ch;
			while((ch=in.read())!=-1) report.append((char)ch);
		}
		return report.toString();
	}

	public static String getMemInfoReport() throws IOException, SQLException {
		StringBuilder report = new StringBuilder(40);
		try (InputStream in = new BufferedInputStream(new FileInputStream(procMeminfo))) {
			int ch;
			while((ch=in.read())!=-1) report.append((char)ch);
		}
		return report.toString();
	}

	/**
	 * Gets the listing of the /etc/xen/auto directory.
	 */
	public static String[] getXenAutoStartLinks() {
		if(xenAutoStartDirectory.isDirectory()) {
			String[] list = xenAutoStartDirectory.list();
			if(list != null) return list;
		}
		return EmptyArrays.EMPTY_STRING_ARRAY;
	}
}
