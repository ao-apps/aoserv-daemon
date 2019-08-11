/*
 * Copyright 2006-2013, 2015, 2016, 2017, 2018, 2019 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.monitor;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.net.Device;
import com.aoindustries.aoserv.client.net.Host;
import com.aoindustries.aoserv.client.web.HttpdServer;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.client.AOServDaemonProtocol;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.FileUtils;
import com.aoindustries.io.stream.StreamableOutput;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.util.BufferManager;
import com.aoindustries.util.StringUtility;
import com.aoindustries.util.WrappedException;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

/**
 * Handles the building of name server processes and files.
 *
 * @author  AO Industries, Inc.
 */
final public class MrtgManager extends BuilderThread {

	public static final int GRAPH_WIDTH = 600;
	public static final int GRAPH_HEIGHT = 150;

	private static final int TOTAL_GRAPH_WIDTH = GRAPH_WIDTH + 100;
	private static final int TOTAL_GRAPH_HEIGHT = GRAPH_HEIGHT + 35;

	private static final UnixFile centosCfgFile = new UnixFile("/etc/mrtg/mrtg.cfg");
	private static final UnixFile centosCfgFileNew = new UnixFile("/etc/mrtg/mrtg.cfg.new");
	private static final UnixFile centosStatsFile = new UnixFile("/var/www/mrtg/stats.html");
	private static final UnixFile centosStatsFileNew = new UnixFile("/var/www/mrtg/stats.html.new");

	private static MrtgManager mrtgManager;

	private MrtgManager() {
	}

	/**
	 * Gets the safe name (used for filenames and resource name) for an Apache server instance.
	 */
	private static String getHttpdServerSafeName(HttpdServer httpdServer) {
		return "hs_" + httpdServer.getPkey();
	}

	/**
	 * Gets the display name for an Apache server instance.
	 */
	private static String getHttpdServerDisplay(OperatingSystemVersion osv, HttpdServer hs) {
		String name = hs.getName();
		int osvId = osv.getPkey();
		if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
			int number = (name == null) ? 1 : Integer.parseInt(name);
			return "Apache Workers #" + number;
		} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
			if(name == null) {
				return "Apache Workers";
			} else {
				return "Apache Workers (" + name + ")";
			}
		} else {
			throw new AssertionError("Unexpected OperatingSystemVersion: " + osv);
		}
	}

	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			Server thisServer = AOServDaemon.getThisServer();
			Host thisHost = thisServer.getHost();
			OperatingSystemVersion osv = thisHost.getOperatingSystemVersion();
			int osvId = osv.getPkey();
			if(
				osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_X86_64
			) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

			int uid_min = thisServer.getUidMin().getId();
			int gid_min = thisServer.getGidMin().getId();

			Server failoverServer = thisServer.getFailoverServer();
			String aoservMrtgBin;
			UnixFile cfgFile;
			UnixFile cfgFileNew;
			UnixFile statsFile;
			UnixFile statsFileNew;
			if(
				osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
				|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
			) {
				aoservMrtgBin = "/opt/aoserv-mrtg/bin";
				cfgFile = centosCfgFile;
				cfgFileNew = centosCfgFileNew;
				statsFile = centosStatsFile;
				statsFileNew = centosStatsFileNew;
			} else throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

			synchronized(rebuildLock) {
				// Make sure mrtg package is installed and enabled
				PackageManager.installPackage(
					PackageManager.PackageName.MRTG,
					() -> {
						if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
							try {
								AOServDaemon.exec("/usr/bin/systemctl", "enable", "mrtg");
								AOServDaemon.exec("/usr/bin/systemctl", "restart", "mrtg");
							} catch(IOException e) {
								throw new WrappedException(e);
							}
						}
					}
				);

				// Make sure aoserv-mrtg package is installed
				PackageManager.installPackage(PackageManager.PackageName.AOSERV_MRTG);

				List<String> dfDevices = getDFDevices();
				List<String> dfSafeNames = getSafeNames(dfDevices);

				// Get the set of HttpdServer that will have their concurrency monitored
				List<HttpdServer> httpdServers = thisServer.getHttpdServers();

				{
					/*
					 * Create the new config file in RAM first
					 */
					ByteArrayOutputStream bout = new ByteArrayOutputStream();
					try (ChainWriter out = new ChainWriter(new OutputStreamWriter(bout, StandardCharsets.UTF_8))) {
						out.print("#\n"
								+ "# Automatically generated by ").print(MrtgManager.class.getName()).print("\n"
								+ "#\n");
						if(
							osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
							|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
						) {
							out.print("HtmlDir: /var/www/mrtg\n"
									+ "ImageDir: /var/www/mrtg\n"
									+ "LogDir: /var/lib/mrtg\n"
									+ "ThreshDir: /var/lib/mrtg\n");
							if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
								// Runs as daemon in CentOS 7
								out.print("RunAsDaemon: yes\n"
										+ "Interval: 5\n"
										+ "NoDetach: yes\n");
							}
						} else throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
						out.print("PageTop[^]: \n"
								+ "  <div style='text-align:center'>\n"
								+ "  <h1>\n"
								+ "  <img src=\"https://aoindustries.com/images/clientarea/accounting/SendInvoices.jpg\" width=\"452\" height=\"127\" alt=\"\" /><br />\n"
								+ "  <span style=\"color:#000000\">").encodeXhtml(thisServer.getHostname());
						if(failoverServer != null) out.print(" on ").encodeXhtml(failoverServer.getHostname());
						out.print("</span>\n"
								+ "  </h1>\n"
								+ "  <hr /><span style=\"font-size:large\">\n"
								+ "  | <a href=\"../../MRTG.ao\">Servers</a> |\n"
								+ "  <a href=\"stats.html\">Stats Overview</a> |\n"
								+ "  <a href=\"load.html\">Load</a> |\n"
								+ "  <a href=\"cpu.html\">CPU</a> |\n"
								+ "  <a href=\"diskio.html\">DiskIO</a> |\n");
						for(int c = 0;c < dfDevices.size(); c++) {
							out.print("  <a href=\"").encodeXmlAttribute(dfSafeNames.get(c)).print(".html\">").encodeXhtml(dfDevices.get(c)).print("</a> |\n");
						}
						out.print("  <a href=\"mem.html\">Memory</a> |\n");
						// Add the network devices
						List<Device> netDevices = thisHost.getNetDevices();
						for(Device netDevice : netDevices) {
							out.print("  <a href=\"").encodeXmlAttribute(netDevice.getDeviceId().getName()).print(".html\">").encodeXhtml(netDevice.getDescription()).print("</a> |\n");
						}
						out.print("  <a href=\"swap.html\">Swap</a> |\n");
						for(HttpdServer httpdServer : httpdServers) {
							String safeName = getHttpdServerSafeName(httpdServer);
							out.print("  <a href=\"").encodeXmlAttribute(safeName).print(".html\">").encodeXhtml(getHttpdServerDisplay(osv, httpdServer)).print("</a> |\n");
						}
						out.print("  </span>\n"
								+ "  </div>\n"
								+ "  <hr />\n"
								+ "\n"
								+ "Interval: 5\n");
						for(Device netDevice : netDevices) {
							String deviceId = netDevice.getDeviceId().getName();
							out.print("\n"
									+ "Target[").print(deviceId).print("]: `").print(aoservMrtgBin).print("/mrtg_net_device ").print(deviceId).print("`\n"
									+ "Options[").print(deviceId).print("]: noinfo, growright, transparent");
							long maxBitRate = netDevice.getMaxBitRate();
							if(maxBitRate == -1) out.print(", nopercent");
							out.print("\n"
									+ "MaxBytes[").print(deviceId).print("]: ").print(maxBitRate == -1 ? 100000000 : netDevice.getMaxBitRate()).print("\n"
									+ "kilo[").print(deviceId).print("]: 1024\n"
									+ "YLegend[").print(deviceId).print("]: Bits per second\n"
									+ "ShortLegend[").print(deviceId).print("]: b/s\n"
									+ "Legend1[").print(deviceId).print("]: Incoming Traffic in Bits per second\n"
									+ "Legend2[").print(deviceId).print("]: Outgoing Traffic in Bits per second\n"
									+ "Legend3[").print(deviceId).print("]: Maximal 5 Minute Incoming Traffic\n"
									+ "Legend4[").print(deviceId).print("]: Maximal 5 Minute Outgoing Traffic\n"
									+ "LegendI[").print(deviceId).print("]:  In:\n"
									+ "LegendO[").print(deviceId).print("]:  Out:\n"
									+ "Timezone[").print(deviceId).print("]: ").print(thisServer.getTimeZone()).print("\n"
									+ "Title[").print(deviceId).print("]: ").print(netDevice.getDescription()).print(" traffic\n"
									+ "PageFoot[").print(deviceId).print("]: <p>\n"
									+ "PageTop[").print(deviceId).print("]: <h2>").print(netDevice.getDescription()).print(" traffic</h2>\n"
									+ "XSize[").print(deviceId).print("]: ").print(GRAPH_WIDTH).print("\n"
									+ "YSize[").print(deviceId).print("]: ").print(GRAPH_HEIGHT).print("\n");
						}
						out.print("\n"
								+ "Target[load]: `").print(aoservMrtgBin).print("/mrtg_load`\n"
								+ "Options[load]: gauge, noinfo, growright, transparent, nopercent, integer\n"
								+ "MaxBytes[load]: 100000\n"
								+ "YLegend[load]: Load Average (x 1000)\n"
								+ "ShortLegend[load]: / 1000\n"
								+ "Legend1[load]: Load Average\n"
								+ "Legend2[load]: Load Average\n"
								+ "Legend3[load]: Load Average\n"
								+ "Legend4[load]: Load Average\n"
								+ "LegendI[load]:  Load:\n"
								+ "LegendO[load]:\n"
								+ "Timezone[load]: ").print(thisServer.getTimeZone()).print("\n"
								+ "Title[load]: Load Average (x 1000)\n"
								+ "PageFoot[load]: <p>\n"
								+ "PageTop[load]: <h2>Load Average (x 1000)</h2>\n"
								+ "XSize[load]: ").print(GRAPH_WIDTH).print("\n"
								+ "YSize[load]: ").print(GRAPH_HEIGHT).print("\n");
						// Figure out the number of CPUs
						int numCPUs = getNumberOfCPUs();
						out.print("\n"
								+ "Target[cpu]: `").print(aoservMrtgBin).print("/mrtg_cpu`\n"
								+ "Options[cpu]: gauge, noinfo, growright, transparent, nopercent, integer\n"
								+ "MaxBytes[cpu]: 100\n"
								+ "YLegend[cpu]: CPU Utilization\n"
								+ "ShortLegend[cpu]: %\n");
						// Handle any even number of CPU
						if(numCPUs >= 6 && (numCPUs & 1) == 0) {
							out.print("Legend1[cpu]: CPU 0 - ").print((numCPUs / 2) - 1).print("\n"
									+ "Legend2[cpu]: CPU ").print(numCPUs / 2).print(" - ").print(numCPUs - 1).print("\n"
									+ "Legend3[cpu]: Maximal 5 Minute\n"
									+ "Legend4[cpu]: Maximal 5 Minute\n"
									+ "LegendI[cpu]:  cpu0-").print((numCPUs / 2) - 1).print(":\n"
									+ "LegendO[cpu]:  cpu").print(numCPUs / 2).print('-').print(numCPUs - 1).print(":\n");
						} else if(numCPUs == 4) {
							out.print("Legend1[cpu]: CPU 0 and 1\n"
									+ "Legend2[cpu]: CPU 2 and 3\n"
									+ "Legend3[cpu]: Maximal 5 Minute\n"
									+ "Legend4[cpu]: Maximal 5 Minute\n"
									+ "LegendI[cpu]:  cpu0+1:\n"
									+ "LegendO[cpu]:  cpu2+3:\n");
						} else if(numCPUs == 2) {
							out.print("Legend1[cpu]: CPU 0\n"
									+ "Legend2[cpu]: CPU 1\n"
									+ "Legend3[cpu]: Maximal 5 Minute\n"
									+ "Legend4[cpu]: Maximal 5 Minute\n"
									+ "LegendI[cpu]:  cpu0:\n"
									+ "LegendO[cpu]:  cpu1:\n");
						} else if(numCPUs == 1) {
							out.print("Legend1[cpu]: System\n"
									+ "Legend2[cpu]: Total\n"
									+ "Legend3[cpu]: Maximal 5 Minute\n"
									+ "Legend4[cpu]: Maximal 5 Minute\n"
									+ "LegendI[cpu]:  system:\n"
									+ "LegendO[cpu]:  total:\n");
						} else {
							throw new IOException("Unsupported number of CPUs: " + numCPUs);
						}
						out.print("Timezone[cpu]: ").print(thisServer.getTimeZone()).print("\n"
								+ "Title[cpu]: Server CPU Utilization (%)\n"
								+ "PageFoot[cpu]: <p>\n"
								+ "PageTop[cpu]: <h2>Server CPU Utilization (%)</h2>\n"
								+ "XSize[cpu]: ").print(GRAPH_WIDTH).print("\n"
								+ "YSize[cpu]: ").print(GRAPH_HEIGHT).print("\n"
								+ "\n"
								+ "Target[mem]: `").print(aoservMrtgBin).print("/mrtg_mem`\n"
								+ "Options[mem]: gauge, noinfo, growright, transparent, nopercent, integer\n"
								+ "MaxBytes[mem]: 100\n"
								+ "YLegend[mem]: % Free memory and swap space\n"
								+ "ShortLegend[mem]: %\n"
								+ "Legend1[mem]: % swap space used\n"
								+ "Legend2[mem]: % memory used\n"
								+ "Legend3[mem]: Maximal 5 Minute\n"
								+ "Legend4[mem]: Maximal 5 Minute\n"
								+ "LegendI[mem]:  Swp:\n"
								+ "LegendO[mem]:  Mem:\n"
								+ "Timezone[mem]: ").print(thisServer.getTimeZone()).print("\n"
								+ "Title[mem]: Server Memory and Swap space\n"
								+ "PageFoot[mem]: <p>\n"
								+ "PageTop[mem]: <h2>Server Memory and Swap space</h2>\n"
								+ "XSize[mem]: ").print(GRAPH_WIDTH).print("\n"
								+ "YSize[mem]: ").print(GRAPH_HEIGHT).print("\n"
								+ "\n"
								+ "Target[diskio]: `").print(aoservMrtgBin).print("/mrtg_diskio`\n"
								+ "Options[diskio]: gauge, noinfo, growright, transparent, nopercent, integer\n"
								+ "MaxBytes[diskio]: 100000000\n"
								+ "YLegend[diskio]: Disk I/O blocks/sec\n"
								+ "ShortLegend[diskio]: blk/s\n"
								+ "Legend1[diskio]: read\n"
								+ "Legend2[diskio]: write\n"
								+ "Legend3[diskio]: Maximal 5 Minute\n"
								+ "Legend4[diskio]: Maximal 5 Minute\n"
								+ "LegendI[diskio]:  read:\n"
								+ "LegendO[diskio]:  write:\n"
								+ "Timezone[diskio]: ").print(thisServer.getTimeZone()).print("\n"
								+ "Title[diskio]: Server Disk I/O (blocks per second)\n"
								+ "PageFoot[diskio]: <p>\n"
								+ "PageTop[diskio]: <h2>Server Disk I/O (blocks per second)</h2>\n"
								+ "XSize[diskio]: ").print(GRAPH_WIDTH).print("\n"
								+ "YSize[diskio]: ").print(GRAPH_HEIGHT).print("\n");
						for(int c = 0; c < dfDevices.size(); c++) {
							String device = dfDevices.get(c);
							String safeName = dfSafeNames.get(c);
							out.print("\n"
									+ "Target[").print(safeName).print("]: `").print(aoservMrtgBin).print("/mrtg_df ").print(device).print("`\n"
									+ "Options[").print(safeName).print("]: gauge, noinfo, growright, transparent, nopercent, integer\n"
									+ "MaxBytes[").print(safeName).print("]: 100\n"
									+ "YLegend[").print(safeName).print("]: % Used space and inodes\n"
									+ "ShortLegend[").print(safeName).print("]: %\n"
									+ "Legend1[").print(safeName).print("]: % space used\n"
									+ "Legend2[").print(safeName).print("]: % inodes used\n"
									+ "Legend3[").print(safeName).print("]: Maximal 5 Minute\n"
									+ "Legend4[").print(safeName).print("]: Maximal 5 Minute\n"
									+ "LegendI[").print(safeName).print("]:  Space:\n"
									+ "LegendO[").print(safeName).print("]:  Inodes:\n"
									+ "Timezone[").print(safeName).print("]: ").print(thisServer.getTimeZone()).print("\n"
									+ "Title[").print(safeName).print("]: ").print(device).print(" Space and Inodes (%)\n"
									+ "PageFoot[").print(safeName).print("]: <p>\n"
									+ "PageTop[").print(safeName).print("]: <h2>").print(device).print(" Space and Inodes (%)</h2>\n"
									+ "XSize[").print(safeName).print("]: ").print(GRAPH_WIDTH).print("\n"
									+ "YSize[").print(safeName).print("]: ").print(GRAPH_HEIGHT).print("\n");
						}
						out.print("\n"
								+ "Target[swap]: `").print(aoservMrtgBin).print("/mrtg_swap`\n"
								+ "Options[swap]: gauge, noinfo, growright, transparent, nopercent, integer\n"
								+ "MaxBytes[swap]: 100000000\n"
								+ "YLegend[swap]: In+Out blocks per second\n"
								+ "ShortLegend[swap]: io blk/s\n"
								+ "Legend1[swap]: swap\n"
								+ "Legend2[swap]: page\n"
								+ "Legend3[swap]: Maximal 5 Minute\n"
								+ "Legend4[swap]: Maximal 5 Minute\n"
								+ "LegendI[swap]:  swap:\n"
								+ "LegendO[swap]:  page:\n"
								+ "Timezone[swap]: ").print(thisServer.getTimeZone()).print("\n"
								+ "Title[swap]: Server Swap and Paging I/O (in+out blocks per second)\n"
								+ "PageFoot[swap]: <p>\n"
								+ "PageTop[swap]: <h2>Server Swap and Paging I/O (in+out blocks per second)</h2>\n"
								+ "XSize[swap]: ").print(GRAPH_WIDTH).print("\n"
								+ "YSize[swap]: ").print(GRAPH_HEIGHT).print("\n");
						for(HttpdServer httpdServer : httpdServers) {
							String safeName = getHttpdServerSafeName(httpdServer);
							String systemdName = httpdServer.getSystemdEscapedName();
							out.print("\n"
									+ "Target[").print(safeName).print("]: `").print(aoservMrtgBin).print("/mrtg_httpd_concurrency '").print(systemdName == null ? "" : systemdName).print("'`\n" // TODO: Which quoting and escaping needed here?
									+ "Options[").print(safeName).print("]: gauge, noinfo, growright, transparent, integer\n"
									+ "MaxBytes[").print(safeName).print("]: ").print(httpdServer.getMaxConcurrency()).print("\n"
									+ "YLegend[").print(safeName).print("]: Number of Workers\n"
									+ "ShortLegend[").print(safeName).print("]: workers\n"
									+ "Legend1[").print(safeName).print("]: Number of Workers\n"
									+ "Legend2[").print(safeName).print("]: Number of Workers\n"
									+ "Legend3[").print(safeName).print("]: Number of Workers\n"
									+ "Legend4[").print(safeName).print("]: Number of Workers\n"
									+ "LegendI[").print(safeName).print("]:  Workers:\n"
									+ "LegendO[").print(safeName).print("]:\n"
									+ "Timezone[").print(safeName).print("]: ").print(thisServer.getTimeZone()).print("\n"
									+ "Title[").print(safeName).print("]: ").print(getHttpdServerDisplay(osv, httpdServer)).print("\n"
									+ "PageFoot[").print(safeName).print("]: <p>\n"
									+ "PageTop[").print(safeName).print("]: <h2>").encodeXhtml(getHttpdServerDisplay(osv, httpdServer)).print("</h2>\n"
									+ "XSize[").print(safeName).print("]: ").print(GRAPH_WIDTH).print("\n"
									+ "YSize[").print(safeName).print("]: ").print(GRAPH_HEIGHT).print("\n");
						}
						out.flush();
					}
					byte[] newFile = bout.toByteArray();
					if(!cfgFile.getStat().exists() || !cfgFile.contentEquals(newFile)) {
						try (OutputStream fileOut = cfgFileNew.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0600, true, uid_min, gid_min)) {
							fileOut.write(newFile);
							fileOut.flush();
						}
						cfgFileNew.renameTo(cfgFile);
						if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
							// Restart service for new config
							AOServDaemon.exec("/usr/bin/systemctl", "restart", "mrtg");
						}
					}
				}

				/*
				 * Rewrite stats.html
				 */
				{
					ByteArrayOutputStream bout = new ByteArrayOutputStream();
					try (ChainWriter out = new ChainWriter(new OutputStreamWriter(bout, StandardCharsets.UTF_8))) {
						if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
							out.print("<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\" \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">\n"
									+ "<!--\n"
									+ "  Automatically generated by ").print(MrtgManager.class.getName()).print("\n"
									+ "-->\n"
									+ "<html xmlns=\"http://www.w3.org/1999/xhtml\" lang=\"en-US\" xml:lang=\"en-US\">\n"
									+ "  <head>\n"
									+ "    <title>Stats Overview</title>\n"
									+ "    <meta http-equiv=\"Refresh\" content=\"300\" />\n"
									+ "    <meta http-equiv=\"Pragma\" content=\"no-cache\" />\n"
									+ "    <meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\" />\n"
									+ "  </head>\n"
									+ "\n"
									+ "  <body style=\"background-color:#ffffff\">\n");
						} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
							out.print("<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.1//EN\" \"http://www.w3.org/TR/xhtml11/dtd/xhtml11.dtd\">\n"
									+ "<html>\n"
									+ "<!-- Begin Head -->\n"
									+ "\t<head>\n"
									+ "\t\t<title>Stats Overview</title>\n"
									+ "\t\t<meta http-equiv=\"refresh\" content=\"300\" />\n"
									+ "\t\t<meta http-equiv=\"pragma\" content=\"no-cache\" />\n"
									+ "\t\t<meta http-equiv=\"cache-control\" content=\"no-cache\" />\n"
									+ "\t\t<meta http-equiv=\"generator\" content=\"").print(MrtgManager.class.getName()).print("\" />\n"
									+ "\t\t<meta http-equiv=\"content-type\" content=\"text/html; charset=utf-8\" />\n"
									+ "\n"
									+ "\t\t<style type=\"text/css\">\n"
									+ "\t\t\tbody {\n"
									+ "\t\t\t\tbackground-color: #ffffff;\n"
									+ "\t\t\t}\n"
									+ "\t\t\tdiv {\n"
									+ "\t\t\t\tborder-bottom: 2px solid #aaa;\n"
									+ "\t\t\t\tpadding-bottom: 10px;\n"
									+ "\t\t\t\tmargin-bottom: 5px;\n"
									+ "\t\t\t}\n"
									+ "\t\t\tdiv h2 {\n"
									+ "\t\t\t\tfont-size: 1.2em;\n"
									+ "\t\t\t}\n"
									+ "\t\t\tdiv.graph img {\n"
									+ "\t\t\t\tmargin: 5px 0;\n"
									+ "\t\t\t}\n"
									+ "\t\t\tdiv.graph table, div#legend table {\n"
									+ "\t\t\t\tfont-size: .8em;\n"
									+ "\t\t\t}\n"
									+ "\t\t\tdiv.graph table td {\n"
									+ "\t\t\t\tpadding: 0 10px;\n"
									+ "\t\t\t\ttext-align: right;\n"
									+ "\t\t\t}\n"
									+ "\t\t\tdiv table .in th, div table td span.in {\n"
									+ "\t\t\t\tcolor: #00cc00;\n"
									+ "\t\t\t}\n"
									+ "\t\t\tdiv table .out th, div table td span.out {\n"
									+ "\t\t\t\tcolor: #0000ff;\n"
									+ "\t\t\t}\n"
									+ "\t\t\tdiv#legend th {\n"
									+ "\t\t\t\ttext-align: right;\n"
									+ "\t\t\t}\n"
									+ "\t\t\tdiv#footer {\n"
									+ "\t\t\t\tborder: none;\n"
									+ "\t\t\t\tfont-size: .8em;\n"
									+ "\t\t\t\tfont-family: Arial, Helvetica, sans-serif;\n"
									+ "\t\t\t\twidth: 476px;\n"
									+ "\t\t\t}\n"
									+ "\t\t\tdiv#footer img {\n"
									+ "\t\t\t\tborder: none;\n"
									+ "\t\t\t\theight: 25px;\n"
									+ "\t\t\t}\n"
									+ "\t\t\tdiv#footer address {\n"
									+ "\t\t\t\ttext-align: right;\n"
									+ "\t\t\t}\n"
									+ "\t\t\tdiv#footer #version {\n"
									+ "\t\t\t\tmargin: 0;\n"
									+ "\t\t\t\tpadding: 0;\n"
									+ "\t\t\t\tfloat: left;\n"
									+ "\t\t\t\twidth: 88px;\n"
									+ "\t\t\t\ttext-align: right;\n"
									+ "\t\t\t}\n"
									+ "\t\t</style>\n"
									+ "\n"
									+ "\t</head>\n"
									+ "<body>\n");
						} else {
							throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
						}
						out.print("      <div style=\"text-align:center\">\n"
								+ "        <h1>\n"
								+ "          <img src=\"https://aoindustries.com/images/clientarea/accounting/SendInvoices.jpg\" width=\"452\" height=\"127\" alt=\"\" /><br />\n"
								+ "	  <span style=\"color:#000000\">").encodeXhtml(thisServer.getHostname());
						if(failoverServer != null) out.print(" on ").encodeXhtml(failoverServer.getHostname());
						out.print("</span>\n"
								+ "        </h1>\n"
								+ "        <hr />\n"
								+ "\n"
								+ "        <span style=\"font-size:large\">\n"
								+ "          | <a href=\"../../MRTG.ao\">Servers</a> |\n"
								+ "          <a href=\"stats.html\">Stats Overview</a> |\n"
								+ "          <a href=\"load.html\">Load</a> |\n"
								+ "          <a href=\"cpu.html\">CPU</a> |\n"
								+ "          <a href=\"diskio.html\">DiskIO</a> |\n");
						for(int c = 0; c < dfDevices.size(); c++) {
							out.print("          <a href=\"").encodeXmlAttribute(dfSafeNames.get(c)).print(".html\">").encodeXhtml(dfDevices.get(c)).print("</a> |\n");
						}
						out.print("          <a href=\"mem.html\">Memory</a> |\n");
						// Add the network devices
						List<Device> netDevices = thisHost.getNetDevices();
						for(Device netDevice : netDevices) {
							out.print("          <a href=\"").encodeXmlAttribute(netDevice.getDeviceId().getName()).print(".html\">").encodeXhtml(netDevice.getDescription()).print("</a> |\n");
						}
						out.print("          <a href=\"swap.html\">Swap</a> |\n");
						for(HttpdServer httpdServer : httpdServers) {
							String safeName = getHttpdServerSafeName(httpdServer);
							out.print("          <a href=\"").encodeXmlAttribute(safeName).print(".html\">").encodeXhtml(getHttpdServerDisplay(osv, httpdServer)).print("</a> |\n");
						}
						out.print("        </span>\n"
								+ "      </div>\n"
								+ "\n"
								+ "      <hr />\n"
								+ "      <h2>Load Average (times 1000)</h2>\n"
								+ "      <p>\n"
								+ "        <a href=\"load.html\"><img style=\"border:0px; display:block;\" width=\"" + TOTAL_GRAPH_WIDTH + "\" height=\"" + TOTAL_GRAPH_HEIGHT + "\" src=\"load-day.png\" alt=\"load\" /></a>\n"
								+ "      </p>\n"
								+ "      <hr />\n"
								+ "      <h2>Server CPU Utilization (%)</h2>\n"
								+ "      <p>\n"
								+ "        <a href=\"cpu.html\"><img style=\"border:0px; display:block;\" width=\"" + TOTAL_GRAPH_WIDTH + "\" height=\"" + TOTAL_GRAPH_HEIGHT + "\" src=\"cpu-day.png\" alt=\"cpu\" /></a>\n"
								+ "      </p>\n"
								+ "      <hr />\n"
								+ "      <h2>Server Disk I/O (blocks per second)</h2>\n"
								+ "      <p>\n"
								+ "        <a href=\"diskio.html\"><img style=\"border:0px; display:block;\" width=\"" + TOTAL_GRAPH_WIDTH + "\" height=\"" + TOTAL_GRAPH_HEIGHT + "\" src=\"diskio-day.png\" alt=\"diskio\" /></a>\n"
								+ "      </p>\n");
						for(int c = 0; c < dfDevices.size(); c++) {
							out.print("      <hr />\n"
									+ "      <h2>").encodeXhtml(dfDevices.get(c)).print(" Space and Inodes (%)</h2>\n"
									+ "      <p>\n"
									+ "        <a href=\"").encodeXmlAttribute(dfSafeNames.get(c)).print(".html\"><img style=\"border:0px; display:block;\" width=\"" + TOTAL_GRAPH_WIDTH + "\" height=\"" + TOTAL_GRAPH_HEIGHT + "\" src=\"").encodeXmlAttribute(dfSafeNames.get(c)).print("-day.png\" alt=\"").encodeXmlAttribute(dfDevices.get(c)).print("\" /></a>\n"
									+ "      </p>\n");
						}
						out.print("      <hr />\n"
								+ "      <h2>Server Memory and Swap space (%)</h2>\n"
								+ "      <p>\n"
								+ "        <a href=\"mem.html\"><img style=\"border:0px; display:block;\" width=\"" + TOTAL_GRAPH_WIDTH + "\" height=\"" + TOTAL_GRAPH_HEIGHT + "\" src=\"mem-day.png\" alt=\"mem\" /></a>\n"
								+ "      </p>\n");
						for(Device netDevice : netDevices) {
							String deviceId = netDevice.getDeviceId().getName();
							out.print("      <hr />\n"
									+ "      <h2>").encodeXhtml(netDevice.getDescription()).print(" traffic</h2>\n"
									+ "      <p>\n"
									+ "        <a href=\"").encodeXmlAttribute(deviceId).print(".html\"><img style=\"border:0px; display:block;\" width=\"" + TOTAL_GRAPH_WIDTH + "\" height=\"" + TOTAL_GRAPH_HEIGHT + "\" src=\"").encodeXmlAttribute(deviceId).print("-day.png\" alt=\"").encodeXmlAttribute(deviceId).print("\" /></a>\n"
									+ "      </p>\n");
						}
						out.print("      <hr />\n"
								+ "      <h2>Server Swap and Paging I/O (in+out blocks per second)</h2>\n"
								+ "      <p>\n"
								+ "        <a href=\"swap.html\"><img style=\"border:0px; display:block;\" width=\"" + TOTAL_GRAPH_WIDTH + "\" height=\"" + TOTAL_GRAPH_HEIGHT + "\" src=\"swap-day.png\" alt=\"swap\" /></a>\n"
								+ "      </p>\n");
						for(HttpdServer httpdServer : httpdServers) {
							String safeName = getHttpdServerSafeName(httpdServer);
							out.print("      <hr />\n"
									+ "      <h2>").encodeXhtml(getHttpdServerDisplay(osv, httpdServer)).print("</h2>\n"
									+ "      <p>\n"
									+ "        <a href=\"").encodeXmlAttribute(safeName).print(".html\"><img style=\"border:0px; display:block;\" width=\"" + TOTAL_GRAPH_WIDTH + "\" height=\"" + TOTAL_GRAPH_HEIGHT + "\" src=\"").encodeXmlAttribute(safeName).print("-day.png\" alt=\"").encodeXmlAttribute(getHttpdServerDisplay(osv, httpdServer)).print("\" /></a>\n"
									+ "      </p>\n");
						}
						if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
							out.print("<!-- Begin MRTG Block -->\n"
									+ "<hr />\n"
									+ "<table style=\"border:0px;\" cellspacing=\"0\" cellpadding=\"0\" summary=\"\">\n"
									+ "  <tr>\n"
									+ "    <td style=\"width:63px;\"><a\n"
									+ "    href=\"http://oss.oetiker.ch/mrtg/doc/mrtg.en.html\"><img\n"
									+ "    alt=\"\" style=\"border:0px; display:block;\" src=\"mrtg-l.png\" width=\"63\" height=\"25\" /></a></td>\n"
									+ "    <td style=\"width:25px;\"><a\n"
									+ "    href=\"http://oss.oetiker.ch/mrtg/doc/mrtg.en.html\"><img\n"
									+ "    alt=\"MRTG\" style=\"border:0px; display:block;\" src=\"mrtg-m.png\" width=\"25\" height=\"25\" /></a></td>\n"
									+ "    <td style=\"width:388px;\"><a\n"
									+ "    href=\"http://oss.oetiker.ch/mrtg/doc/mrtg.en.html\"><img\n"
									+ "    alt=\"\" style=\"border:0px; display:block;\" src=\"mrtg-r.png\" width=\"388\" height=\"25\" /></a></td>\n"
									+ "  </tr>\n"
									+ "</table>\n"
									+ "<table style=\"margin-top:4px; border:0px;\" cellspacing=\"0\" cellpadding=\"0\" summary=\"\">\n"
									+ "  <tr valign=\"top\">\n"
									+ "  <td><span style=\"font-size:x-large\">\n"
									+ "  <a href=\"https://tobi.oetiker.ch/hp/\">Tobias Oetiker</a>\n"
									+ "  <a href=\"mailto:oetiker@ee.ethz.ch\">&lt;oetiker@ee.ethz.ch&gt;</a>\n"
									+ "  and&#160;<a href=\"http://www.bungi.com\">Dave&#160;Rand</a>&#160;<a href=\"mailto:dlr@bungi.com\">&lt;dlr@bungi.com&gt;</a></span>\n"
									+ "  </td>\n"
									+ "</tr>\n"
									+ "</table>\n"
									+ "<!-- End MRTG Block -->\n");
						} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
							out.print("<hr />\n"
									+ "<!-- Begin MRTG Block -->\n"
									+ "\t\t<div id=\"footer\">\n"
									+ "\t\t\t<a href=\"http://oss.oetiker.ch/mrtg/\"><img src=\"mrtg-l.png\" width=\"63\" title=\"MRTG\" alt=\"MRTG\" /><img src=\"mrtg-m.png\" width=\"25\" title=\"MRTG\" alt=\"MRTG\" /><img src=\"mrtg-r.png\" width=\"388\" title=\"Multi Router Traffic Grapher\" alt=\"Multi Router Traffic Grapher\" /></a>\n"
									+ "\t\t\t<p id=\"version\">2.17.4</p>\n"
									+ "\t\t\t<address>\n"
									+ "\t\t\t\t<a href=\"http://tobi.oetiker.ch/\">Tobias Oetiker</a>\n"
									+ "\t\t\t\t<a href=\"mailto:tobi+mrtglink@oetiker.ch\">&lt;tobi@oetiker.ch&gt;</a><br />\n"
									+ "and\t\t\t\t<a href=\"http://www.bungi.com/\">Dave Rand</a>\n"
									+ "\t\t\t\t<a href=\"mailto:dlr@bungi.com\">&lt;dlr@bungi.com&gt;</a>\n"
									+ "\t\t\t</address>\n"
									+ "\t\t</div>\n"
									+ "\t\t<!-- End MRTG Block -->\n");
						} else {
							throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
						}
						out.print("  </body>\n"
								+ "</html>\n");
						out.flush();
					}
					byte[] newFile = bout.toByteArray();
					if(!statsFile.getStat().exists() || !statsFile.contentEquals(newFile)) {
						try (OutputStream fileOut = statsFileNew.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0644, true, uid_min, gid_min)) {
							fileOut.write(newFile);
							fileOut.flush();
						}
						statsFileNew.renameTo(statsFile);
					}
				}
			}
			return true;
		} catch(RuntimeException | IOException | SQLException T) {
			LogFactory.getLogger(MrtgManager.class).log(Level.SEVERE, null, T);
			return false;
		}
	}

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
				&& AOServDaemonConfiguration.isManagerEnabled(MrtgManager.class)
				&& mrtgManager == null
			) {
				System.out.print("Starting MrtgManager: ");
				// Must be a supported operating system
				if(
					osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
				) {
					AOServConnector conn = AOServDaemon.getConnector();
					mrtgManager = new MrtgManager();
					conn.getLinux().getServer().addTableListener(mrtgManager, 0);
					conn.getLinux().getTimeZone().addTableListener(mrtgManager, 0);
					conn.getNet().getDevice().addTableListener(mrtgManager, 0);
					conn.getNet().getDeviceId().addTableListener(mrtgManager, 0);
					conn.getNet().getHost().addTableListener(mrtgManager, 0);
					conn.getWeb().getHttpdServer().addTableListener(mrtgManager, 0);
					System.out.println("Done");
				} else {
					System.out.println("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild mrtg.cfg";
	}

	/**
	 * Reads /proc/cpuinfo and determines the number of CPUs.
	 * @return
	 * @throws IOException
	 */
	public static int getNumberOfCPUs() throws IOException {
		try (BufferedReader in = new BufferedReader(new InputStreamReader(new FileInputStream("/proc/cpuinfo")))) {
			int count = 0;
			String line;
			while((line = in.readLine())!=null) {
				if(line.startsWith("processor\t: ")) count++;
			}
			return count;
		}
	}

	/**
	 * Gets the list of devices for df commands.  When in a failover state, returns empty list.
	 * @return
	 * @throws IOException
	 * @throws SQLException
	 */
	private static List<String> getDFDevices() throws IOException, SQLException {
		Server thisServer = AOServDaemon.getThisServer();
		if(thisServer.getFailoverServer() != null) return Collections.emptyList();
		OperatingSystemVersion osv = thisServer.getHost().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		List<String> devices = new ArrayList<>();
		String listPartitionsCommand;
		if(
			osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
			|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
		) {
			listPartitionsCommand = "/opt/aoserv-daemon/bin/list_partitions";
		} else throw new AssertionError("Unsupporter OperatingSystemVersion: " + osv);

		Process P = Runtime.getRuntime().exec(new String[] {listPartitionsCommand});
		try {
			P.getOutputStream().close();
			try (BufferedReader in = new BufferedReader(new InputStreamReader(P.getInputStream()))) {
				String line;
				while((line = in.readLine()) != null) {
					if(devices.contains(line)) {
						LogFactory.getLogger(MrtgManager.class).log(Level.WARNING, null, new Throwable("Warning: duplicate device from list_partitions: " + line));
					} else {
						devices.add(line);
					}
				}
			}
		} finally {
			try {
				int retCode = P.waitFor();
				if(retCode != 0) throw new IOException("Non-zero return value from list_partitions: " + retCode);
			} catch(InterruptedException err) {
				InterruptedIOException ioErr = new InterruptedIOException();
				ioErr.initCause(err);
				throw ioErr;
			}
		}

		Collections.sort(devices);
		return devices;
	}

	private static List<String> getSafeNames(List<String> devices) throws IOException {
		if(devices.isEmpty()) return Collections.emptyList();
		List<String> safeNames = new ArrayList<>(devices.size());
		for(String device : devices) {
			final String safeName;
			switch (device) {
				case "/var/lib/pgsql.aes256.img":
					safeName = "pgsqlaes256";
					break;
				case "/www.aes256.img":
					safeName = "wwwaes256";
					break;
				case "/ao.aes256.img":
					safeName = "aoaes256";
					break;
				case "/ao.copy.aes256.img":
					safeName = "aocopyaes256";
					break;
				case "/dev/mapper/ao":
					safeName = "aoluks";
					break;
				default: {
					if(device.startsWith("/dev/mapper/")) {
						device = "mapper_" + device.substring(12).replace('-', '_');
					} else if(device.startsWith("/dev/")) {
						device = device.substring(5);
					}	// All characters should now be a-z, A-Z, 0-9 or _
					if(device.isEmpty()) throw new IOException("Empty device name: " + device);
					for(int c = 0; c < device.length(); c++) {
						char ch = device.charAt(c);
						if(
							(ch < 'a' || ch > 'z')
							&& (ch < 'A' || ch > 'Z')
							&& (ch < '0' || ch > '9')
							&& ch != '_'
						) throw new IOException("Invalid character in device.  ch=" + ch + ", device=" + device);
					}
					safeName = device;
				}
			}
			safeNames.add(safeName);
		}
		return safeNames;
	}

	public static final File centosMrtgDirectory = new File("/var/www/mrtg");

	public static void getMrtgFile(String filename, StreamableOutput out) throws IOException, SQLException {
		OperatingSystemVersion osv = AOServDaemon.getThisServer().getHost().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		File mrtgDirectory;
		if(
			osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
			|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
		) {
			mrtgDirectory = centosMrtgDirectory;
		} else {
			throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
		}
		InputStream in = null;
		try {
			File file = new File(mrtgDirectory, filename);
			if(filename.endsWith(".html")) {
				// Find-replace the hard-coded charset created by MRTG
				in = new ByteArrayInputStream(
					StringUtility.replace(
						FileUtils.readFileAsString(file, StandardCharsets.UTF_8),
						"charset=iso-8859-1",
						"charset=utf-8"
					).getBytes(StandardCharsets.UTF_8)
				);
			} else {
				in = new FileInputStream(file);
			}
			byte[] buff = BufferManager.getBytes();
			try {
				int ret;
				while((ret = in.read(buff, 0, BufferManager.BUFFER_SIZE)) != -1) {
					out.write(AOServDaemonProtocol.NEXT);
					out.writeShort(ret);
					out.write(buff, 0, ret);
				}
			} finally {
				BufferManager.release(buff, false);
			}
		} finally {
			if(in != null) in.close();
		}
	}
}
