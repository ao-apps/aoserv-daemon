package com.aoindustries.aoserv.daemon.httpd;

/*
 * Copyright 2005-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.client.HttpdSiteBind;
import com.aoindustries.aoserv.client.HttpdSiteURL;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.LinuxGroup;
import com.aoindustries.aoserv.client.OperatingSystemVersion;
import com.aoindustries.aoserv.client.Server;
import com.aoindustries.aoserv.client.Shell;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.AOServDaemonConfiguration;
import com.aoindustries.aoserv.daemon.backup.BackupManager;
import com.aoindustries.aoserv.daemon.client.AOServDaemonProtocol;
import com.aoindustries.aoserv.daemon.util.BuilderThread;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.CompressedDataOutputStream;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.util.BufferManager;
import com.aoindustries.util.StringUtility;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Controls the configuration files for AWStats and provides access to the AWStats system.
 *
 * @author  AO Industries, Inc.
 */
final public class AWStatsManager extends BuilderThread {

    private static AWStatsManager awstatsManager;

    private AWStatsManager() {
    }

    private static final Object rebuildLock=new Object();
    protected void doRebuild() throws IOException, SQLException {
        HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
        final File awstatsDirectory = new File(osConfig.getAwstatsDirectory());
        final File binDirectory     = new File(osConfig.getAwstatsBinDirectory());
        final File configDirectory  = new File(osConfig.getAwstatsConfigDirectory());
        final File hostsDirectory   = new File(osConfig.getAwstatsHostsDirectory());

        // Resolve the UID and GID before obtaining the lock
        AOServer aoServer=AOServDaemon.getThisAOServer();
        Server server = aoServer.getServer();
        int awstatsUID=aoServer.getLinuxServerAccount(LinuxAccount.AWSTATS).getUID().getID();
        int awstatsGID=aoServer.getLinuxServerGroup(LinuxGroup.AWSTATS).getGID().getID();

        // RAM is used to verify config files before committing to the filesystem
        ByteArrayOutputStream byteBuff=new ByteArrayOutputStream();
        ChainWriter out=new ChainWriter(byteBuff);

        final Stat tempStat = new Stat();

        synchronized(rebuildLock) {
            // Get some variables that will be used throughout the method
            List<File> deleteFileList=new ArrayList<File>();

            // Get the list of all files and directories under /etc/awstats
            Map<String,Object> existingConfigFiles=new HashMap<String,Object>();
            String[] configList=configDirectory.list();
            for(String filename: configList) existingConfigFiles.put(filename, null);

            // Get the list of all files and directories under /var/lib/awstats/hosts
            Map<String,Object> existingHostDirectories=new HashMap<String,Object>();
            String[] list=hostsDirectory.list();
            for(String filename : list) existingHostDirectories.put(filename, null);

            // These are cleared and reused for each iteration of the loop
            Map<String,Object> usedHostnames=new HashMap<String,Object>();
            Map<String,Object> usedLogs=new HashMap<String,Object>();

            // Iterate through each website on this server
            for(HttpdSite site : aoServer.getHttpdSites()) {
                String siteName=site.getSiteName();
                String configFilename="awstats."+siteName+".conf";
                existingConfigFiles.remove(configFilename);
                existingHostDirectories.remove(siteName);

                // Resolve the primary URL
                HttpdSiteURL primaryHttpdSiteURL = site.getPrimaryHttpdSiteURL();
                String primaryURL=primaryHttpdSiteURL==null ? siteName : primaryHttpdSiteURL.getHostname();
                usedHostnames.clear();
                usedHostnames.put(primaryURL, null);

                // Verify the /etc/awstats config file
                byteBuff.reset();
                out.print("#\n"
                        + "# See ").print(binDirectory.getPath()).print("/wwwroot/cgi-bin/awstats.model.conf\n"
                        + "#\n"
                        + "LogFile=\"").print(hostsDirectory.getPath()).print('/').print(siteName).print("/logview.sh |\"\n"
                        + "LogType=W\n"
                        + "LogFormat=1\n"
                        + "LogSeparator=\" \"\n"
                        + "SiteDomain=\"").print(primaryHttpdSiteURL).print("\"\n"
                        + "HostAliases=\"");
                // For each bind, show both the hostnames and the IP addresses
                List<HttpdSiteBind> binds=site.getHttpdSiteBinds();
                int count=0;
                for(HttpdSiteBind bind : binds) {
                    // Add the hostnames
                    for(HttpdSiteURL url : bind.getHttpdSiteURLs()) {
                        String hostname=url.getHostname();
                        if(!usedHostnames.containsKey(hostname)) {
                            usedHostnames.put(hostname, null);
                            if(count>0) out.print(' ');
                            out.print(hostname);
                            count++;
                        }
                    }
                    // Add the IP address, skipping wildcard or loopback IP addresses
                    IPAddress ip=bind.getHttpdBind().getNetBind().getIPAddress();
                    if(
                        !ip.isWildcard()
                        && !ip.getNetDevice().getNetDeviceID().isLoopback()
                    ) {
                        String addr=ip.getIPAddress();
                        if(!usedHostnames.containsKey(addr)) {
                            usedHostnames.put(addr, null);
                            if(count>0) out.print(' ');
                            out.print(addr);
                            count++;
                        }
                    }
                }
                if(count==0) out.print(primaryHttpdSiteURL);
                out.print("\"\n"
                        + "DNSLookup=2\n"
                        + "DirData=\"").print(hostsDirectory.getPath()).print('/').print(siteName).print("/data\"\n"
                        + "DirCgi=\"").print(binDirectory.getPath()).print("/wwwroot/cgi-bin\"\n"
                        + "DirIcons=\"https://www.aoindustries.com/clientarea/control/httpd/awstats_icons\"\n"
                        + "AllowToUpdateStatsFromBrowser=0\n"
                        + "AllowFullYearView=0\n"
                        + "EnableLockForUpdate=1\n"
                        + "DNSStaticCacheFile=\"").print(awstatsDirectory.getPath()).print("/dnscache.txt\"\n"
                        + "DNSLastUpdateCacheFile=\"").print(hostsDirectory.getPath()).print('/').print(siteName).print("/dnscachelastupdate.txt\"\n"
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
                Set<String> finishedIPs = new HashSet<String>();
                for(IPAddress ia : server.getIPAddresses()) {
                    if(!ia.isWildcard()) {
                        String ip = ia.getIPAddress();
                        if(!finishedIPs.contains(ip)) {
                            if(!finishedIPs.isEmpty()) out.print(' ');
                            out.print(ip);
                            finishedIPs.add(ip);
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
                        + "DirLang=\"").print(binDirectory.getPath()).print("/wwwroot/cgi-bin/lang\"\n"
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
                        //+ "HTMLHeadSection=\"<DIV align='center'><A target='_new' href='http://www.aoindustries.com/'><IMG src='https://www.aoindustries.com/images/clientarea/accounting/SendInvoices.jpg' border='0' width='452' height='127' alt='Hosted by AO Industries, Inc.'></A><BR>Back to <A target='_top' href='../../../AWStats.ao'>Control Panels</A></DIV>\"\n"
                        //+ "HTMLEndSection=\"<B>Hosted by <A target='_new' href='http://www.aoindustries.com/'>AO Industries, Inc.</A></B>\"\n"
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
                out.flush();
                byte[] newFileContent=byteBuff.toByteArray();

                // Write to disk if file missing or doesn't match
                UnixFile configFile=new UnixFile(configDirectory, configFilename);
                configFile.getStat(tempStat);
                if(!tempStat.exists() || !configFile.contentEquals(newFileContent)) {
                    OutputStream fileOut=configFile.getSecureOutputStream(
                        UnixFile.ROOT_UID,
                        awstatsGID,
                        0640,
                        true
                    );
                    try {
                        fileOut.write(newFileContent);
                    } finally {
                        fileOut.close();
                    }
                } else {
                    if(tempStat.getMode()!=0640) configFile.setMode(0640);
                    if(
                        tempStat.getUID()!=UnixFile.ROOT_UID
                        || tempStat.getGID()!=awstatsGID
                    ) configFile.chown(UnixFile.ROOT_UID, awstatsGID);
                }

                // Make sure /var/lib/awstats/hosts/<site_name> directory exists and has the proper permissions
                UnixFile hostDirectory=new UnixFile(hostsDirectory, siteName);
                hostDirectory.getStat(tempStat);
                if(!tempStat.exists()) hostDirectory.mkdir(false, 0750, UnixFile.ROOT_UID, awstatsGID);
                else {
                    if(tempStat.getMode()!=0750) hostDirectory.setMode(0750);
                    if(
                        tempStat.getUID()!=UnixFile.ROOT_UID
                        || tempStat.getGID()!=awstatsGID
                    ) hostDirectory.chown(UnixFile.ROOT_UID, awstatsGID);
                }

                // Make sure /var/lib/awstats/hosts/<site_name>/data directory exists and has the proper permissions
                UnixFile dataDirectory=new UnixFile(hostDirectory, "data", false);
                dataDirectory.getStat(tempStat);
                if(!tempStat.exists()) dataDirectory.mkdir(false, 0750, awstatsUID, awstatsGID);
                else {
                    if(tempStat.getMode()!=0750) dataDirectory.setMode(0750);
                    if(
                        tempStat.getUID()!=awstatsUID
                        || tempStat.getGID()!=awstatsGID
                    ) dataDirectory.chown(awstatsUID, awstatsGID);
                }

                // dnscachelastupdate.txt
                UnixFile dnscachelastupdate=new UnixFile(hostDirectory, "dnscachelastupdate.txt", false);
                dnscachelastupdate.getStat(tempStat);
                if(!tempStat.exists()) dnscachelastupdate.getSecureOutputStream(awstatsUID, awstatsGID, 0640, false);
                else {
                    if(tempStat.getMode()==0640) dnscachelastupdate.setMode(0640);
                    if(
                        tempStat.getUID()!=awstatsUID
                        || tempStat.getGID()!=awstatsGID
                    ) dnscachelastupdate.chown(awstatsUID, awstatsGID);
                }

                // logview.sh
                byteBuff.reset();
                out.print("#!/bin/bash\n");
                out.print(binDirectory.getPath()).print("/tools/logresolvemerge.pl");
                usedLogs.clear();
                for(HttpdSiteBind bind : binds) {
                    String access_log=bind.getAccessLog();
                    if(!usedLogs.containsKey(access_log)) {
                        usedLogs.put(access_log, null);
                        out.print(" \\\n"
                                + "\t'").print(access_log).print(".1.gz' \\\n"
                                + "\t'").print(access_log).print('\'');
                    }
                }
                if(usedLogs.isEmpty()) out.print(" \\\n\t/dev/null\n");
                else out.print('\n');
                out.flush();
                newFileContent=byteBuff.toByteArray();

                // Write to disk if file missing or doesn't match
                UnixFile logviewFile=new UnixFile(hostDirectory, "logview.sh", false);
                logviewFile.getStat(tempStat);
                if(!tempStat.exists() || !logviewFile.contentEquals(newFileContent)) {
                    OutputStream fileOut=logviewFile.getSecureOutputStream(
                        UnixFile.ROOT_UID,
                        awstatsGID,
                        0750,
                        true
                    );
                    try {
                        fileOut.write(newFileContent);
                    } finally {
                        fileOut.close();
                    }
                } else {
                    if(tempStat.getMode()!=0750) logviewFile.setMode(0750);
                    if(
                        tempStat.getUID()!=UnixFile.ROOT_UID
                        || tempStat.getGID()!=awstatsGID
                    ) logviewFile.chown(UnixFile.ROOT_UID, awstatsGID);
                }

                // runascgi.sh
                byteBuff.reset();
                out.print("#!/bin/bash\n"
                        + "cd ").print(binDirectory.getPath()).print("/wwwroot/cgi-bin\n"
                        + "export DOCUMENT_ROOT=").print(awstatsDirectory.getPath()).print("\n"
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
                        + "export SCRIPT_FILENAME='").print(binDirectory.getPath()).print("/wwwroot/cgi-bin/awstats.pl'\n"
                        + "export SCRIPT_NAME='/cgi-bin/awstats.pl'\n"
                        + "export SCRIPT_URI='http://aoindustries.com/cgi-bin/awstats.pl'\n"
                        + "export SCRIPT_URL='/cgi-bin/awstats.pl'\n"
                        + "export SERVER_ADDR='64.62.174.39'\n"
                        + "export SERVER_ADMIN='webmaster@aoindustries.com'\n"
                        + "export SERVER_NAME='").print(siteName).print("'\n"
                        + "export SERVER_PORT='80'\n"
                        + "export SERVER_PROTOCOL='HTTP/1.1'\n"
                        + "export SERVER_SOFTWARE='Apache-AdvancedExtranetServer/1.3.31'\n")
                        .print(binDirectory.getPath()).print("/wwwroot/cgi-bin/awstats.pl -config='").print(siteName).print("'\n");
                out.flush();
                newFileContent=byteBuff.toByteArray();

                // Write to disk if file missing or doesn't match
                UnixFile runascgiFile=new UnixFile(hostDirectory, "runascgi.sh", false);
                runascgiFile.getStat(tempStat);
                if(!tempStat.exists() || !runascgiFile.contentEquals(newFileContent)) {
                    OutputStream fileOut=runascgiFile.getSecureOutputStream(
                        UnixFile.ROOT_UID,
                        awstatsGID,
                        0750,
                        true
                    );
                    try {
                        fileOut.write(newFileContent);
                    } finally {
                        fileOut.close();
                    }
                } else {
                    if(tempStat.getMode()!=0750) runascgiFile.setMode(0750);
                    if(
                        tempStat.getUID()!=UnixFile.ROOT_UID
                        || tempStat.getGID()!=awstatsGID
                    ) runascgiFile.chown(UnixFile.ROOT_UID, awstatsGID);
                }

                // update.sh
                byteBuff.reset();
                out.print("#!/bin/bash\n"
                        + "cd '").print(hostsDirectory.getPath()).print('/').print(siteName).print("'\n"
                        + "/usr/bin/perl ").print(binDirectory.getPath()).print("/wwwroot/cgi-bin/awstats.pl -config='").print(siteName).print("' -update\n");
                out.flush();
                newFileContent=byteBuff.toByteArray();

                // Write to disk if file missing or doesn't match
                UnixFile updateFile=new UnixFile(hostDirectory, "update.sh", false);
                updateFile.getStat(tempStat);
                if(!tempStat.exists() || !updateFile.contentEquals(newFileContent)) {
                    OutputStream fileOut=updateFile.getSecureOutputStream(
                        UnixFile.ROOT_UID,
                        awstatsGID,
                        0750,
                        true
                    );
                    try {
                        fileOut.write(newFileContent);
                    } finally {
                        fileOut.close();
                    }
                } else {
                    if(tempStat.getMode()!=0750) updateFile.setMode(0750);
                    if(
                        tempStat.getUID()!=UnixFile.ROOT_UID
                        || tempStat.getGID()!=awstatsGID
                    ) updateFile.chown(UnixFile.ROOT_UID, awstatsGID);
                }
            }

            // Remove any extra config files
            for(String filename : existingConfigFiles.keySet()) deleteFileList.add(new File(configDirectory, filename));

            // Remove any files or directories that should not exist
            for(String filename : existingHostDirectories.keySet()) deleteFileList.add(new File(hostsDirectory, filename));

            /*
             * Back up the files scheduled for removal.
             */
            int deleteFileListLen=deleteFileList.size();
            if(deleteFileListLen>0) {
                // Get the next backup filename
                File backupFile=BackupManager.getNextBackupFile();
                BackupManager.backupFiles(deleteFileList, backupFile);

                /*
                 * Remove the files that have been backed up.
                 */
                for(int c=0;c<deleteFileListLen;c++) {
                    File file=deleteFileList.get(c);
                    new UnixFile(file).secureDeleteRecursive();
                }
            }
        }
    }

    public static void start() throws IOException, SQLException {
        AOServer thisAOServer=AOServDaemon.getThisAOServer();
        int osv=thisAOServer.getServer().getOperatingSystemVersion().getPkey();

        synchronized(System.out) {
            if(
                // Nothing is done for these operating systems
                osv!=OperatingSystemVersion.CENTOS_5DOM0_I686
                && osv!=OperatingSystemVersion.CENTOS_5DOM0_X86_64
                // Check config after OS check so config entry not needed
                && AOServDaemonConfiguration.isManagerEnabled(AWStatsManager.class)
                && awstatsManager==null
            ) {
                System.out.print("Starting AWStatsManager: ");
                AOServConnector connector=AOServDaemon.getConnector();
                awstatsManager=new AWStatsManager();
                connector.getHttpdSites().addTableListener(awstatsManager, 0);
                connector.getHttpdSiteBinds().addTableListener(awstatsManager, 0);
                connector.getHttpdSiteURLs().addTableListener(awstatsManager, 0);
                connector.getIpAddresses().addTableListener(awstatsManager, 0);
                connector.getNetBinds().addTableListener(awstatsManager, 0);
                System.out.println("Done");
            }
        }
    }

    public String getProcessTimerDescription() {
        return "Rebuild AWStats";
    }
    
    @Override
    public long getProcessTimerMaximumTime() {
        return 15L*60*1000;
    }

    public static void getAWStatsFile(String siteName, String path, String queryString, CompressedDataOutputStream out) throws IOException, SQLException {
        HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
        int osv=AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPkey();
        if(
            osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
            && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
            && osv!=OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
        ) throw new SQLException("Unsupport OperatingSystemVersion: "+osv);

        if("awstats.pl".equals(path)) {
            // Check the queryStrings
            String escapedSiteName=StringUtility.replace(siteName, '.', "\\.");
            if(
                queryString.equals("")
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
            ) {
                String runascgi;
                if(osv==OperatingSystemVersion.MANDRIVA_2006_0_I586) {
                    runascgi = "/var/lib/awstats/hosts/"+siteName+"/runascgi.sh";
                } else if(
                    osv==OperatingSystemVersion.REDHAT_ES_4_X86_64
                    || osv==OperatingSystemVersion.CENTOS_5_I686_AND_X86_64
                ) {
                    runascgi = "/var/opt/awstats-6/hosts/"+siteName+"/runascgi.sh";
                } else throw new SQLException("Unsupported OperatingSystemVersion: "+osv);
                String[] cmd={
                    "/bin/su",
                    "-s",
                    Shell.BASH,
                    "-c",
                    runascgi+" '"+queryString+"'",
                    LinuxAccount.AWSTATS
                };
                Process P = Runtime.getRuntime().exec(cmd);
                try {
                    P.getOutputStream().close();
                    BufferedReader in=new BufferedReader(new InputStreamReader(P.getInputStream()));
                    try {
                        // Skip the headers
                        String line;
                        while((line=in.readLine())!=null && line.length()>0) {
                            // Intentional empty block
                        }

                        // Write the rest in blocks
                        byte[] buff=BufferManager.getBytes();
                        try {
                            char[] chars=BufferManager.getChars();
                            try {
                                int ret;
                                while((ret=in.read(chars, 0, BufferManager.BUFFER_SIZE))!=-1) {
                                    // Convert to bytes by simple cast
                                    for(int c=0;c<ret;c++) buff[c]=(byte)chars[c];

                                    out.write(AOServDaemonProtocol.NEXT);
                                    out.writeShort(ret);
                                    out.write(buff, 0, ret);
                                }
                            } finally {
                                BufferManager.release(chars);
                            }
                        } finally {
                            BufferManager.release(buff);
                        }
                    } finally {
                        in.close();
                    }
                } finally {
                    // Wait for the process to complete
                    try {
                        int retCode = P.waitFor();
                        if(retCode!=0) throw new IOException("Non-zero return status: "+retCode);
                    } catch (InterruptedException err) {
                        AOServDaemon.reportWarning(err, null);
                    }
                }
            } else {
                throw new IOException("Unsupported queryString for awstats.pl: "+queryString);
            }
        } else {
            if(path.startsWith("icon/") && path.indexOf("..")==-1) {
                final File iconDirectory = new File(osConfig.getAwstatsIconDirectory());
                File file=new File(iconDirectory, path.substring(5));
                FileInputStream in=new FileInputStream(file);
                try {
                    byte[] buff=BufferManager.getBytes();
                    try {
                        int ret;
                        while((ret=in.read(buff, 0, BufferManager.BUFFER_SIZE))!=-1) {
                            out.write(AOServDaemonProtocol.NEXT);
                            out.writeShort(ret);
                            out.write(buff, 0, ret);
                        }
                    } finally {
                        BufferManager.release(buff);
                    }
                } finally {
                    in.close();
                }
            } else throw new IOException("Unsupported path: "+path);
        }
    }
}
