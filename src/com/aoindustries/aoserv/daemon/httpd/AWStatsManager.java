/*
 * Copyright 2005-2013, 2015, 2016, 2017, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.httpd;

import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.distribution.OperatingSystemVersion;
import com.aoindustries.aoserv.client.linux.Group;
import com.aoindustries.aoserv.client.linux.GroupServer;
import com.aoindustries.aoserv.client.linux.Server;
import com.aoindustries.aoserv.client.linux.Shell;
import com.aoindustries.aoserv.client.linux.User;
import com.aoindustries.aoserv.client.linux.UserServer;
import com.aoindustries.aoserv.client.net.Host;
import com.aoindustries.aoserv.client.net.IpAddress;
import com.aoindustries.aoserv.client.validator.UnixPath;
import com.aoindustries.aoserv.client.web.Site;
import com.aoindustries.aoserv.client.web.VirtualHost;
import com.aoindustries.aoserv.client.web.VirtualHostName;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.backup.BackupManager;
import com.aoindustries.aoserv.daemon.client.AOServDaemonProtocol;
import com.aoindustries.aoserv.daemon.unix.linux.PackageManager;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.aoserv.daemon.util.DaemonFileUtils;
import com.aoindustries.encoding.ChainWriter;
import com.aoindustries.io.CompressedDataOutputStream;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.net.InetAddress;
import com.aoindustries.util.BufferManager;
import com.aoindustries.util.StringUtility;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Controls the configuration files for AWStats and provides access to the AWStats system.
 *
 * @author  AO Industries, Inc.
 */
final public class AWStatsManager extends BuilderThread {

	private static final Logger logger = Logger.getLogger(AWStatsManager.class.getName());

	private static AWStatsManager awstatsManager;

	private AWStatsManager() {
	}

	private static final Object rebuildLock = new Object();
	@Override
	protected boolean doRebuild() {
		try {
			HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
			final File configDirectory = new File(osConfig.getAwstatsConfigDirectory().toString());
			final File hostsDirectory  = new File(osConfig.getAwstatsHostsDirectory().toString());
			final PackageManager.PackageName awstatsPackageName = osConfig.getAwstatsPackageName();

			// Resolve the UID and GID before obtaining the lock
			Server thisAoServer = AOServDaemon.getThisAOServer();
			Host thisServer = thisAoServer.getServer();
			int uid_min = thisAoServer.getUidMin().getId();
			int gid_min = thisAoServer.getGidMin().getId();

			synchronized(rebuildLock) {
				Set<UnixFile> restorecon = new LinkedHashSet<>();
				try {
					// Get some variables that will be used throughout the method
					List<File> deleteFileList = new ArrayList<>();
					// Get the list of all files and directories under /etc/awstats
					Set<String> existingConfigFiles = new HashSet<>();
					{
						String[] list = configDirectory.list();
						if(list != null) {
							existingConfigFiles.addAll(Arrays.asList(list));
						}
					}
					// Get the list of all files and directories under /var/opt/awstats[-6]/hosts
					Set<String> existingHostDirectories = new HashSet<>();
					{
						String[] list = hostsDirectory.list();
						if(list != null) {
							existingHostDirectories.addAll(Arrays.asList(list));
						}
					}
					final List<Site> sites = thisAoServer.getHttpdSites();
					if(!sites.isEmpty()) {
						// Install awstats[_6] package when first needed
						if(awstatsPackageName != null) {
							PackageManager.installPackage(awstatsPackageName);
						}
						// User and group required
						final int awstatsUID;
						{
							UserServer awstatsLSA = thisAoServer.getLinuxServerAccount(User.AWSTATS);
							if(awstatsLSA == null) throw new SQLException("Unable to find UserServer: " + User.AWSTATS);
							awstatsUID = awstatsLSA.getUid().getId();
						}
						final int awstatsGID;
						{
							GroupServer awstatsLSG = thisAoServer.getLinuxServerGroup(Group.AWSTATS);
							if(awstatsLSG == null) throw new SQLException("Unable to find GroupServer: " + Group.AWSTATS);
							awstatsGID = awstatsLSG.getGid().getId();
						}
						// Some more values used within the loop
						final File awstatsVarDirectory = new File(osConfig.getAwstatsVarDirectory().toString());
						final File binDirectory        = new File(osConfig.getAwstatsBinDirectory().toString());
						// These are cleared and reused for each iteration of the loop
						Set<String> usedHostnames = new HashSet<>();
						Set<UnixPath> usedLogs = new HashSet<>();
						// RAM is used to verify config files before committing to the filesystem
						ByteArrayOutputStream bout = new ByteArrayOutputStream();
						// Iterate through each website on this server
						for(Site site : sites) {
							List<VirtualHost> binds = site.getHttpdSiteBinds();

							String siteName = site.getName();
							String configFilename = "awstats." + siteName + ".conf";
							existingConfigFiles.remove(configFilename);
							existingHostDirectories.remove(siteName);

							// Resolve the primary URL
							VirtualHostName primaryHttpdSiteURL = site.getPrimaryHttpdSiteURL();
							String primaryURL = (primaryHttpdSiteURL == null) ? siteName : primaryHttpdSiteURL.getHostname().toString();
							usedHostnames.clear();
							usedHostnames.add(primaryURL);

							// Verify the /etc/awstats config file
							byte[] newFileContent;
							bout.reset();
							try (ChainWriter out = new ChainWriter(bout)) {
								if(osConfig == HttpdOperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64) {
									out.print("#\n"
											+ "# Generated by ").print(AWStatsManager.class.getName()).print("\n"
											+ "#\n"
											+ "# See ").print(binDirectory).print("/wwwroot/cgi-bin/awstats.model.conf\n"
											+ "#\n"
											+ "LogFile=\"").print(hostsDirectory).print('/').print(siteName).print("/logview.sh |\"\n"
											+ "LogType=W\n"
											+ "LogFormat=1\n"
											+ "LogSeparator=\" \"\n"
											+ "SiteDomain=\"").print(primaryHttpdSiteURL).print("\"\n"
											+ "HostAliases=\"");
									// For each bind, show both the hostnames and the IP addresses
									int count=0;
									for(VirtualHost bind : binds) {
										// Add the hostnames
										for(VirtualHostName url : bind.getHttpdSiteURLs()) {
											String hostname=url.getHostname().toString();
											if(usedHostnames.add(hostname)) {
												if(count>0) out.print(' ');
												out.print(hostname);
												count++;
											}
										}
										// Add the IP address, skipping wildcard or loopback IP addresses
										IpAddress ip=bind.getHttpdBind().getNetBind().getIpAddress();
										InetAddress ia = ip.getInetAddress();
										if(
											!ia.isUnspecified()
											&& !ip.getDevice().getDeviceId().isLoopback()
										) {
											String addr=ia.toString();
											if(usedHostnames.add(addr)) {
												if(count>0) out.print(' ');
												out.print(addr);
												count++;
											}
										}
									}
									if(count==0) out.print(primaryHttpdSiteURL);
									out.print("\"\n"
											+ "DNSLookup=2\n"
											+ "DirData=\"").print(hostsDirectory).print('/').print(siteName).print("/data\"\n"
											+ "DirCgi=\"").print(binDirectory).print("/wwwroot/cgi-bin\"\n"
											+ "DirIcons=\"https://aoindustries.com/clientarea/control/httpd/awstats_icons\"\n"
											+ "AllowToUpdateStatsFromBrowser=0\n"
											+ "AllowFullYearView=0\n"
											+ "EnableLockForUpdate=1\n"
											+ "DNSStaticCacheFile=\"").print(awstatsVarDirectory).print("/dnscache.txt\"\n"
											+ "DNSLastUpdateCacheFile=\"").print(hostsDirectory).print('/').print(siteName).print("/dnscachelastupdate.txt\"\n"
											+ "SkipDNSLookupFor=\"\"\n"
											+ "AllowAccessFromWebToAuthenticatedUsersOnly=0\n"
											+ "AllowAccessFromWebToFollowingAuthenticatedUsers=\"\"\n"
											+ "AllowAccessFromWebToFollowingIPAddresses=\"\"\n"
											+ "CreateDirDataIfNotExists=0\n"
											+ "BuildHistoryFormat=text\n"
											+ "BuildReportFormat=html\n"
											+ "SaveDatabaseFilesWithPermissionsForEveryone=0\n"
											+ "PurgeLogFile=0\n"
											+ "ArchiveLogRecords=0\n"
											+ "KeepBackupOfHistoricFiles=1\n"
											+ "DefaultFile=\"index.html\"\n"
											+ "SkipHosts=\"");
									Set<String> finishedIPs = new HashSet<>();
									for(IpAddress ip : thisServer.getIPAddresses()) {
										InetAddress ia = ip.getInetAddress();
										if(!ia.isUnspecified()) {
											String addr = ia.toString();
											if(!finishedIPs.contains(addr)) {
												if(!finishedIPs.isEmpty()) out.print(' ');
												out.print(addr);
												finishedIPs.add(addr);
											}
										}
									}
									out.print("\"\n"
											+ "SkipUserAgents=\"\"\n"
											+ "SkipFiles=\"");
									String awstatsSkipFiles=site.getAwstatsSkipFiles();
									if(awstatsSkipFiles!=null) out.print(awstatsSkipFiles);
									out.print("\"\n"
											+ "OnlyHosts=\"\"\n"
											+ "OnlyUserAgents=\"\"\n"
											+ "OnlyFiles=\"\"\n"
											+ "NotPageList=\"css js class gif jpg jpeg png bmp ico swf\"\n"
											+ "ValidHTTPCodes=\"200 304\"\n"
											+ "ValidSMTPCodes=\"1 250\"\n"
											+ "AuthenticatedUsersNotCaseSensitive=0\n"
											+ "URLNotCaseSensitive=0\n"
											+ "URLWithAnchor=0\n"
											+ "URLQuerySeparators=\"?;\"\n"
											+ "URLWithQuery=0\n"
											+ "URLWithQueryWithOnlyFollowingParameters=\"\"\n"
											+ "URLWithQueryWithoutFollowingParameters=\"\"\n"
											+ "URLReferrerWithQuery=0\n"
											+ "WarningMessages=1\n"
											+ "ErrorMessages=\"\"\n"
											+ "DebugMessages=0\n"
											+ "NbOfLinesForCorruptedLog=50\n"
											+ "WrapperScript=\"\"\n"
											+ "DecodeUA=0\n"
											+ "MiscTrackerUrl=\"/js/awstats_misc_tracker.js\"\n"
											+ "LevelForBrowsersDetection=2\n"
											+ "LevelForOSDetection=2\n"
											+ "LevelForRefererAnalyze=2\n"
											+ "LevelForRobotsDetection=2\n"
											+ "LevelForSearchEnginesDetection=2\n"
											+ "LevelForKeywordsDetection=2\n"
											+ "LevelForFileTypesDetection=2\n"
											+ "LevelForWormsDetection=0\n"
											+ "UseFramesWhenCGI=1\n"
											+ "DetailedReportsOnNewWindows=1\n"
											+ "Expires=0\n"
											+ "MaxRowsInHTMLOutput=1000\n"
											+ "Lang=\"auto\"\n"
											+ "DirLang=\"").print(binDirectory).print("/wwwroot/cgi-bin/lang\"\n"
											+ "ShowMenu=1\n"
											+ "ShowSummary=UVPHB\n"
											+ "ShowMonthStats=UVPHB\n"
											+ "ShowDaysOfMonthStats=VPHB\n"
											+ "ShowDaysOfWeekStats=PHB\n"
											+ "ShowHoursStats=PHB\n"
											+ "ShowDomainsStats=PHB\n"
											+ "ShowHostsStats=PHBL\n"
											+ "ShowAuthenticatedUsers=0\n"
											+ "ShowRobotsStats=HBL\n"
											+ "ShowWormsStats=0\n"
											+ "ShowEMailSenders=0\n"
											+ "ShowEMailReceivers=0\n"
											+ "ShowSessionsStats=1\n"
											+ "ShowPagesStats=PBEX\n"
											+ "ShowFileTypesStats=HB\n"
											+ "ShowFileSizesStats=0\n"
											+ "ShowOSStats=1\n"
											+ "ShowBrowsersStats=1\n"
											+ "ShowScreenSizeStats=0\n"
											+ "ShowOriginStats=PH\n"
											+ "ShowKeyphrasesStats=1\n"
											+ "ShowKeywordsStats=1\n"
											+ "ShowMiscStats=anjdfrqwp\n"
											+ "ShowHTTPErrorsStats=1\n"
											+ "ShowSMTPErrorsStats=0\n"
											+ "ShowClusterStats=0\n"
											+ "AddDataArrayMonthStats=1\n"
											+ "AddDataArrayShowDaysOfMonthStats=1\n"
											+ "AddDataArrayShowDaysOfWeekStats=1\n"
											+ "AddDataArrayShowHoursStats=1\n"
											+ "IncludeInternalLinksInOriginSection=0\n"
											+ "MaxNbOfDomain=10\n"
											+ "MinHitDomain=1\n"
											+ "MaxNbOfHostsShown=10\n"
											+ "MinHitHost=1\n"
											+ "MaxNbOfLoginShown=10\n"
											+ "MinHitLogin=1\n"
											+ "MaxNbOfRobotShown=10\n"
											+ "MinHitRobot=1\n"
											+ "MaxNbOfPageShown=10\n"
											+ "MinHitFile=1\n"
											+ "MaxNbOfOsShown=10\n"
											+ "MinHitOs=1\n"
											+ "MaxNbOfBrowsersShown=10\n"
											+ "MinHitBrowser=1\n"
											+ "MaxNbOfScreenSizesShown=5\n"
											+ "MinHitScreenSize=1\n"
											+ "MaxNbOfWindowSizesShown=5\n"
											+ "MinHitWindowSize=1\n"
											+ "MaxNbOfRefererShown=10\n"
											+ "MinHitRefer=1\n"
											+ "MaxNbOfKeyphrasesShown=10\n"
											+ "MinHitKeyphrase=1\n"
											+ "MaxNbOfKeywordsShown=10\n"
											+ "MinHitKeyword=1\n"
											+ "MaxNbOfEMailsShown=20\n"
											+ "MinHitEMail=1\n"
											+ "FirstDayOfWeek=1\n"
											+ "ShowFlagLinks=\"\"\n"
											+ "ShowLinksOnUrl=1\n"
											+ "UseHTTPSLinkForUrl=\"\"\n"
											+ "MaxLengthOfShownURL=64\n"
											// TODO: Get from brands
											+ "HTMLHeadSection=\"<div style='text-align:center; font-size:larger'><a target='_parent' href='https://aoindustries.com/'><img src='https://aoindustries.com/images/clientarea/accounting/SendInvoices.jpg' style='border:0px;' width='452' height='127' alt='Hosted by AO Industries, Inc.' /></a><br />Back to <a target='_parent' href='../../../AWStats.ao'>Control Panels</a></div>\"\n"
											+ "HTMLEndSection=\"<b>Hosted by <a target='_parent' href='https://aoindustries.com/'>AO Industries, Inc.</a></b>\"\n"
											+ "Logo=\"\"\n"
											+ "LogoLink=\"\"\n"
											+ "BarWidth=260\n"
											+ "BarHeight=90\n"
											+ "StyleSheet=\"\"\n"
											+ "color_Background=\"FFFFFF\"\n"
											+ "color_TableBGTitle=\"CCCCDD\"\n"
											+ "color_TableTitle=\"000000\"\n"
											+ "color_TableBG=\"CCCCDD\"\n"
											+ "color_TableRowTitle=\"FFFFFF\"\n"
											+ "color_TableBGRowTitle=\"ECECEC\"\n"
											+ "color_TableBorder=\"ECECEC\"\n"
											+ "color_text=\"000000\"\n"
											+ "color_textpercent=\"606060\"\n"
											+ "color_titletext=\"000000\"\n"
											+ "color_weekend=\"EAEAEA\"\n"
											+ "color_link=\"0011BB\"\n"
											+ "color_hover=\"605040\"\n"
											+ "color_u=\"FFAA66\"\n"
											+ "color_v=\"F4F090\"\n"
											+ "color_p=\"4477DD\"\n"
											+ "color_h=\"66DDEE\"\n"
											+ "color_k=\"2EA495\"\n"
											+ "color_s=\"8888DD\"\n"
											+ "color_e=\"CEC2E8\"\n"
											+ "color_x=\"C1B2E2\"\n"
											+ "ExtraTrackedRowsLimit=500\n");
								} else if(osConfig == HttpdOperatingSystemConfiguration.CENTOS_7_X86_64) {
									out.print("#\n"
											+ "# The AWStats configuration for the \"").print(siteName).print("\" site.\n"
											+ "#\n"
											+ "# Generated by ").print(AWStatsManager.class.getName()).print("\n"
											+ "#\n"
											+ "# See ").print(configDirectory).print("/awstats.conf.inc\n"
											+ "#\n"
											+ "\n"
											+ "#\n"
											+ "# Common settings:\n"
											+ "#\n"
											+ "Include \"").print(configDirectory).print("/awstats.conf.inc\"\n"
											+ "\n"
											+ "#\n"
											+ "# Per-site settings:"
											+ "#\n"
											+ "LogFile=\"").print(hostsDirectory).print('/').print(siteName).print("/logview.sh |\"\n"
											+ "SiteDomain=\"").print(primaryHttpdSiteURL).print("\"\n"
											+ "HostAliases=\"");
									// For each bind, show both the hostnames and the IP addresses
									int count=0;
									for(VirtualHost bind : binds) {
										// Add the hostnames
										for(VirtualHostName url : bind.getHttpdSiteURLs()) {
											String hostname=url.getHostname().toString();
											if(usedHostnames.add(hostname)) {
												if(count>0) out.print(' ');
												out.print(hostname);
												count++;
											}
										}
										// Add the IP address, skipping wildcard or loopback IP addresses
										IpAddress ip=bind.getHttpdBind().getNetBind().getIpAddress();
										InetAddress ia = ip.getInetAddress();
										if(
											!ia.isUnspecified()
											&& !ip.getDevice().getDeviceId().isLoopback()
										) {
											String addr=ia.toString();
											if(usedHostnames.add(addr)) {
												if(count>0) out.print(' ');
												out.print(addr);
												count++;
											}
										}
									}
									if(count==0) out.print(primaryHttpdSiteURL);
									out.print("\"\n"
											+ "DirData=\"").print(hostsDirectory).print('/').print(siteName).print("/data\"\n"
											+ "DNSLastUpdateCacheFile=\"").print(hostsDirectory).print('/').print(siteName).print("/dnscachelastupdate.txt\"\n"
											+ "SkipHosts=\"");
									Set<String> finishedIPs = new HashSet<>();
									for(IpAddress ip : thisServer.getIPAddresses()) {
										InetAddress ia = ip.getInetAddress();
										if(!ia.isUnspecified()) {
											String addr = ia.toString();
											if(!finishedIPs.contains(addr)) {
												if(!finishedIPs.isEmpty()) out.print(' ');
												out.print(addr);
												finishedIPs.add(addr);
											}
										}
									}
									out.print("\"\n"
											+ "SkipFiles=\"");
									String awstatsSkipFiles=site.getAwstatsSkipFiles();
									if(awstatsSkipFiles!=null) out.print(awstatsSkipFiles);
									out.print("\"\n"
											// TODO: Get from brands
											+ "HTMLHeadSection=\"<div style='text-align:center; font-size:larger'><a target='_parent' href='https://aoindustries.com/'><img src='https://aoindustries.com/images/clientarea/accounting/SendInvoices.jpg' style='border:0px;' width='452' height='127' alt='Hosted by AO Industries, Inc.' /></a><br />Back to <a target='_parent' href='../../../AWStats.ao'>Control Panels</a></div>\"\n"
											+ "HTMLEndSection=\"<b>Hosted by <a target='_parent' href='https://aoindustries.com/'>AO Industries, Inc.</a></b>\"\n"
											+ "Logo=\"\"\n"
											+ "LogoLink=\"\"\n");
								} else {
									System.out.println("Unsupported HttpdOperatingSystemConfiguration: " + osConfig);
								}
							}
							newFileContent = bout.toByteArray();
							// Write to disk if file missing or doesn't match
							DaemonFileUtils.atomicWrite(
								new UnixFile(configDirectory, configFilename),
								newFileContent,
								0640,
								UnixFile.ROOT_UID,
								awstatsGID,
								null,
								restorecon
							);

							// Make sure /var/opt/awstats[-6]/hosts/<site_name> directory exists and has the proper permissions
							UnixFile hostDirectory = new UnixFile(hostsDirectory, siteName);
							DaemonFileUtils.mkdir(hostDirectory, 0750, UnixFile.ROOT_UID, awstatsGID);

							// Make sure /var/opt/awstats[-6]/hosts/<site_name>/data directory exists and has the proper permissions
							UnixFile dataDirectory = new UnixFile(hostDirectory, "data", false);
							DaemonFileUtils.mkdir(dataDirectory, 0750, awstatsUID, awstatsGID);

							// dnscachelastupdate.txt
							UnixFile dnscachelastupdate = new UnixFile(hostDirectory, "dnscachelastupdate.txt", false);
							DaemonFileUtils.createEmptyFile(dnscachelastupdate, 0640, awstatsUID, awstatsGID);

							// logview.sh
							bout.reset();
							try (ChainWriter out = new ChainWriter(bout)) {
								if(osConfig == HttpdOperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64) {
									out.print("#!/bin/bash\n");
									out.print(binDirectory).print("/tools/logresolvemerge.pl");
									usedLogs.clear();
									for(VirtualHost bind : binds) {
										UnixPath access_log = bind.getAccessLog();
										if(usedLogs.add(access_log)) {
											out.print(" \\\n"
													+ "\t'").print(access_log).print(".1.gz' \\\n"
													+ "\t'").print(access_log).print('\'');
										}
									}
									if(usedLogs.isEmpty()) out.print(" \\\n\t/dev/null\n");
									else out.print('\n');
								} else if(osConfig == HttpdOperatingSystemConfiguration.CENTOS_7_X86_64) {
									out.print("#!/usr/bin/bash\n");
									out.print(binDirectory).print("/tools/logresolvemerge.pl");
									usedLogs.clear();
									for(VirtualHost bind : binds) {
										UnixPath access_log = bind.getAccessLog();
										if(usedLogs.add(access_log)) {
											// Note: /etc/logrotate.d/httpd-sites contains "delaycompress" so ".1" instead of ".1.gz"
											out.print(" \\\n"
													+ "\t'").print(access_log).print(".1' \\\n"
													+ "\t'").print(access_log).print('\'');
										}
									}
									if(usedLogs.isEmpty()) out.print(" \\\n\t/dev/null\n");
									else out.print('\n');
								} else {
									System.out.println("Unsupported HttpdOperatingSystemConfiguration: " + osConfig);
								}
							}
							newFileContent = bout.toByteArray();
							// Write to disk if file missing or doesn't match
							DaemonFileUtils.atomicWrite(
								new UnixFile(hostDirectory, "logview.sh", false),
								newFileContent,
								0750,
								UnixFile.ROOT_UID,
								awstatsGID,
								null,
								restorecon
							);

							// runascgi.sh
							bout.reset();
							try (ChainWriter out = new ChainWriter(bout)) {
								if(osConfig == HttpdOperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64) {
									out.print("#!/bin/bash\n"
											+ "cd ").print(binDirectory).print("/wwwroot/cgi-bin\n"
											+ "export DOCUMENT_ROOT=").print(awstatsVarDirectory).print("\n"
											+ "export GATEWAY_INTERFACE=CGI/1.1\n"
											+ "export HTTP_ACCEPT='text/html;q=0.9,text/plain;q=0.8,image/png,*/*;q=0.5'\n"
											+ "export HTTP_ACCEPT_CHARSET='ISO-8859-1,utf-8;q=0.7,*;q=0.7'\n"
											+ "export HTTP_ACCEPT_ENCODING=\n"
											+ "export HTTP_ACCEPT_LANGUAGE='en-us,en;q=0.5'\n"
											+ "export HTTP_CONNECTION='keep-alive'\n"
											+ "export HTTP_HOST='").print(siteName).print("'\n"
											+ "export HTTP_KEEP_ALIVE='300'\n"
											+ "export HTTP_USER_AGENT='Mozilla/5.0 (X11; U; Linux i686; en-US; rv:1.7.12) Gecko/20050920'\n"
											+ "export QUERY_STRING=\"$1\"\n"
											+ "export REMOTE_ADDR='66.160.183.1'\n"
											+ "export REMOTE_PORT='4583'\n"
											+ "export REQUEST_METHOD='GET'\n"
											+ "if [ \"$QUERY_STRING\" = \"\" ]\n"
											+ "then\n"
											+ "\texport REQUEST_URI='/cgi-bin/awstats.pl'\n"
											+ "else\n"
											+ "\texport REQUEST_URI=\"/cgi-bin/awstats.pl?${QUERY_STRING}\"\n"
											+ "fi\n"
											+ "export SCRIPT_FILENAME='").print(binDirectory).print("/wwwroot/cgi-bin/awstats.pl'\n"
											+ "export SCRIPT_NAME='/cgi-bin/awstats.pl'\n"
											+ "export SCRIPT_URI='http://aoindustries.com/cgi-bin/awstats.pl'\n"
											+ "export SCRIPT_URL='/cgi-bin/awstats.pl'\n"
											+ "export SERVER_ADDR='64.62.174.39'\n"
											+ "export SERVER_ADMIN='support@aoindustries.com'\n"
											+ "export SERVER_NAME='").print(siteName).print("'\n"
											+ "export SERVER_PORT='80'\n"
											+ "export SERVER_PROTOCOL='HTTP/1.1'\n"
											+ "export SERVER_SOFTWARE='Apache-AdvancedExtranetServer/1.3.31'\n")
											.print(binDirectory).print("/wwwroot/cgi-bin/awstats.pl -config='").print(siteName).print("'\n");
								} else if(osConfig == HttpdOperatingSystemConfiguration.CENTOS_7_X86_64) {
									out.print("#!/usr/bin/bash\n"
											+ "cd '").print(binDirectory).print("/wwwroot/cgi-bin'\n"
											+ "export DOCUMENT_ROOT='").print(awstatsVarDirectory).print("'\n"
											+ "export GATEWAY_INTERFACE='CGI/1.1'\n"
											+ "export HTTP_ACCEPT='text/html;q=0.9,text/plain;q=0.8,image/png,*/*;q=0.5'\n"
											+ "export HTTP_ACCEPT_CHARSET='ISO-8859-1,utf-8;q=0.7,*;q=0.7'\n"
											+ "export HTTP_ACCEPT_ENCODING=\n"
											+ "export HTTP_ACCEPT_LANGUAGE='en-us,en;q=0.5'\n"
											+ "export HTTP_CONNECTION='keep-alive'\n"
											+ "export HTTP_HOST='").print(siteName).print("'\n"
											+ "export HTTP_KEEP_ALIVE='300'\n"
											+ "export HTTP_USER_AGENT='Mozilla/5.0 (X11; U; Linux i686; en-US; rv:1.7.12) Gecko/20050920'\n"
											+ "export QUERY_STRING=\"$1\"\n"
											+ "export REMOTE_ADDR='66.160.183.1'\n"
											+ "export REMOTE_PORT='4583'\n"
											+ "export REQUEST_METHOD='GET'\n"
											+ "if [ \"$QUERY_STRING\" = '' ]\n"
											+ "then\n"
											+ "\texport REQUEST_URI='/cgi-bin/awstats.pl'\n"
											+ "else\n"
											+ "\texport REQUEST_URI=\"/cgi-bin/awstats.pl?${QUERY_STRING}\"\n"
											+ "fi\n"
											+ "export SCRIPT_FILENAME='").print(binDirectory).print("/wwwroot/cgi-bin/awstats.pl'\n"
											+ "export SCRIPT_NAME='/cgi-bin/awstats.pl'\n"
											+ "export SCRIPT_URI='http://aoindustries.com/cgi-bin/awstats.pl'\n"
											+ "export SCRIPT_URL='/cgi-bin/awstats.pl'\n"
											+ "export SERVER_ADDR='64.62.174.39'\n"
											+ "export SERVER_ADMIN='support@aoindustries.com'\n"
											+ "export SERVER_NAME='").print(siteName).print("'\n"
											+ "export SERVER_PORT='80'\n"
											+ "export SERVER_PROTOCOL='HTTP/1.1'\n"
											+ "export SERVER_SOFTWARE='Apache-AdvancedExtranetServer/1.3.31'\n")
											.print(binDirectory).print("/wwwroot/cgi-bin/awstats.pl -config='").print(siteName).print("'\n");
								} else {
									System.out.println("Unsupported HttpdOperatingSystemConfiguration: " + osConfig);
								}
							}
							newFileContent = bout.toByteArray();
							// Write to disk if file missing or doesn't match
							DaemonFileUtils.atomicWrite(
								new UnixFile(hostDirectory, "runascgi.sh", false),
								newFileContent,
								0750,
								UnixFile.ROOT_UID,
								awstatsGID,
								null,
								restorecon
							);

							// update.sh
							bout.reset();
							try (ChainWriter out = new ChainWriter(bout)) {
								if(osConfig == HttpdOperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64) {
									out.print("#!/bin/bash\n"
											+ "cd '").print(hostsDirectory).print('/').print(siteName).print("'\n"
											+ "/usr/bin/perl ").print(binDirectory).print("/wwwroot/cgi-bin/awstats.pl -config='").print(siteName).print("' -update\n");
								} else if(osConfig == HttpdOperatingSystemConfiguration.CENTOS_7_X86_64) {
									out.print("#!/usr/bin/bash\n"
											+ "cd '").print(hostsDirectory).print('/').print(siteName).print("'\n"
											+ "/usr/bin/perl '").print(binDirectory).print("/wwwroot/cgi-bin/awstats.pl' -config='").print(siteName).print("' -update\n");
								} else {
									System.out.println("Unsupported HttpdOperatingSystemConfiguration: " + osConfig);
								}
							}
							newFileContent = bout.toByteArray();
							// Write to disk if file missing or doesn't match
							DaemonFileUtils.atomicWrite(
								new UnixFile(hostDirectory, "update.sh", false),
								newFileContent,
								0750,
								UnixFile.ROOT_UID,
								awstatsGID,
								null,
								restorecon
							);
						}
					}

					// Remove any extra config files
					for(String filename : existingConfigFiles) {
						if(
							!filename.equals("awstats.conf.inc")
							// Also keep any rpmnew or rpmsave files
							&& !filename.startsWith("awstats.conf.inc.rpm")
						) {
							File configFile = new File(configDirectory, filename);
							if(logger.isLoggable(Level.INFO)) logger.info("Scheduling for removal: " + configFile);
							deleteFileList.add(configFile);
						}
					}

					// Remove any files or directories that should not exist
					for(String filename : existingHostDirectories) {
						File hostsFile = new File(hostsDirectory, filename);
						if(logger.isLoggable(Level.INFO)) logger.info("Scheduling for removal: " + hostsFile);
						deleteFileList.add(hostsFile);
					}

					// Back-up and delete the files scheduled for removal.
					BackupManager.backupAndDeleteFiles(deleteFileList);

					// Uninstall awstats[_6] package when not needed
					if(
						awstatsPackageName != null
						&& sites.isEmpty()
						&& AOServDaemonConfiguration.isPackageManagerUninstallEnabled()
					) {
						PackageManager.removePackage(awstatsPackageName);
					}
				} finally {
					DaemonFileUtils.restorecon(restorecon);
				}
			}
			return true;
		} catch(ThreadDeath TD) {
			throw TD;
		} catch(Throwable T) {
			LogFactory.getLogger(AWStatsManager.class).log(Level.SEVERE, null, T);
			return false;
		}
	}

	public static void start() throws IOException, SQLException {
		Server thisAOServer = AOServDaemon.getThisAOServer();
		OperatingSystemVersion osv = thisAOServer.getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();

		synchronized(System.out) {
			if(
				// Nothing is done for these operating systems
				osvId != OperatingSystemVersion.CENTOS_5_DOM0_I686
				&& osvId != OperatingSystemVersion.CENTOS_5_DOM0_X86_64
				&& osvId != OperatingSystemVersion.CENTOS_7_DOM0_X86_64
				// Check config after OS check so config entry not needed
				&& AOServDaemonConfiguration.isManagerEnabled(AWStatsManager.class)
				&& awstatsManager == null
			) {
				System.out.print("Starting AWStatsManager: ");
				// Must be a supported operating system
				if(
					osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
					|| osvId == OperatingSystemVersion.CENTOS_7_X86_64
				) {
					AOServConnector conn = AOServDaemon.getConnector();
					awstatsManager = new AWStatsManager();
					conn.getHttpdSites().addTableListener(awstatsManager, 0);
					conn.getHttpdSiteBinds().addTableListener(awstatsManager, 0);
					conn.getHttpdSiteURLs().addTableListener(awstatsManager, 0);
					conn.getIpAddresses().addTableListener(awstatsManager, 0);
					conn.getNetBinds().addTableListener(awstatsManager, 0);
					System.out.println("Done");
				} else {
					System.out.println("Unsupported OperatingSystemVersion: " + osv);
				}
			}
		}
	}

	@Override
	public String getProcessTimerDescription() {
		return "Rebuild AWStats";
	}

	@Override
	public long getProcessTimerMaximumTime() {
		return 15L*60*1000;
	}

	public static void getAWStatsFile(String siteName, String path, String queryString, CompressedDataOutputStream out) throws IOException, SQLException {
		HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
		OperatingSystemVersion osv = AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion();
		int osvId = osv.getPkey();
		if(
			osvId != OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
			&& osvId != OperatingSystemVersion.CENTOS_7_X86_64
		) throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);

		if("awstats.pl".equals(path)) {
			// Check the queryStrings
			String escapedSiteName = StringUtility.replace(siteName, '.', "\\.");
			if(
				// Protect for use in '...' below:
				queryString.indexOf('\'') == -1
				&& queryString.indexOf(0) == -1
				&& (
					// May not contain zero terminator
					queryString.isEmpty()
					|| queryString.equals("framename=mainright")
					|| queryString.equals("framename=mainleft")
					|| queryString.matches("^framename=mainright&output=\\w*$")
					|| queryString.matches("^month=\\d*&year=\\d*&output=main&config="+escapedSiteName+"&framename=\\w*$")
					|| queryString.matches("^month=\\d*&year=\\d*&config="+escapedSiteName+"&framename=\\w*$")
					|| queryString.matches("^month=\\d*&year=\\d*&config="+escapedSiteName+"&framename=\\w*&output=\\w*$")
					|| queryString.matches("^hostfilter=(\\w|\\.)*&hostfilterex=(\\w|\\.)*&output=\\w*&config="+escapedSiteName+"&year=\\d*&month=\\d*&framename=\\w*$")
					|| queryString.matches("^hostfilter=(\\w|\\.)*&hostfilterex=(\\w|\\.)*&output=\\w*&config="+escapedSiteName+"&framename=\\w*$")
					|| queryString.matches("^urlfilter=(\\w|\\.)*&urlfilterex=(\\w|\\.)*&output=\\w*&config="+escapedSiteName+"&year=\\d*&month=\\d*&framename=\\w*$")
					|| queryString.matches("^urlfilter=(\\w|\\.)*&urlfilterex=(\\w|\\.)*&output=\\w*&config="+escapedSiteName+"&framename=\\w*$")
					|| queryString.matches("^month=\\d*&year=\\d*&output=\\w*&config="+escapedSiteName+"&framename=\\w*$")
				)
			) {
				String runascgi;
				if(osvId == OperatingSystemVersion.CENTOS_5_I686_AND_X86_64) {
					runascgi = "/var/opt/awstats-6/hosts/" + siteName + "/runascgi.sh";
				} else if(osvId == OperatingSystemVersion.CENTOS_7_X86_64) {
					runascgi = "/var/opt/awstats/hosts/" + siteName + "/runascgi.sh";
				} else {
					throw new AssertionError("Unsupported OperatingSystemVersion: " + osv);
				}
				String[] cmd = {
					"/bin/su",
					"-s",
					Shell.BASH.toString(),
					"-c",
					runascgi + " '" + queryString + "'",
					User.AWSTATS.toString()
				};
				Process P = Runtime.getRuntime().exec(cmd);
				try {
					P.getOutputStream().close();
					try (BufferedReader in = new BufferedReader(new InputStreamReader(P.getInputStream()))) {
						// Skip the headers
						String line;
						while((line = in.readLine()) != null && line.length() > 0) {
							// Intentional empty block
						}

						// Write the rest in blocks
						byte[] buff = BufferManager.getBytes();
						try {
							char[] chars = BufferManager.getChars();
							try {
								int ret;
								while((ret = in.read(chars, 0, BufferManager.BUFFER_SIZE))!=-1) {
									// Convert to bytes by simple cast - assumes ISO8859-1 encoding
									for(int c = 0; c < ret; c++) buff[c] = (byte)chars[c];

									out.write(AOServDaemonProtocol.NEXT);
									out.writeShort(ret);
									out.write(buff, 0, ret);
								}
							} finally {
								BufferManager.release(chars, false);
							}
						} finally {
							BufferManager.release(buff, false);
						}
					}
				} finally {
					// Wait for the process to complete
					try {
						int retCode = P.waitFor();
						if(retCode!=0) throw new IOException("Non-zero return status: "+retCode);
					} catch (InterruptedException err) {
						IOException ioErr = new InterruptedIOException();
						ioErr.initCause(err);
						throw ioErr;
					}
				}
			} else {
				throw new IOException("Unsupported queryString for awstats.pl: "+queryString);
			}
		} else {
			if(
				path.startsWith("icon/")
				&& !path.contains("..")
				&& path.indexOf(0) == -1
			) {
				final File iconDirectory = new File(osConfig.getAwstatsIconDirectory().toString());
				File file = new File(iconDirectory, path.substring("icon/".length()));
				try (FileInputStream in = new FileInputStream(file)) {
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
				}
			} else {
				throw new IOException("Unsupported path: " + path);
			}
		}
	}
}
