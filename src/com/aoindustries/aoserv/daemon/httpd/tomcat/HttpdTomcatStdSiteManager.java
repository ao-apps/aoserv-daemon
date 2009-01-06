package com.aoindustries.aoserv.daemon.httpd.tomcat;

/*
 * Copyright 2007-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.HttpdJKProtocol;
import com.aoindustries.aoserv.client.HttpdSharedTomcat;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.client.HttpdTomcatStdSite;
import com.aoindustries.aoserv.client.HttpdTomcatVersion;
import com.aoindustries.aoserv.client.HttpdWorker;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.httpd.HttpdOperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.util.FileUtils;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

/**
 * Manages HttpdTomcatStdSite configurations.
 *
 * @author  AO Industries, Inc.
 */
abstract class HttpdTomcatStdSiteManager<TC extends TomcatCommon> extends HttpdTomcatSiteManager<TC> {

    /**
     * Gets the specific manager for one type of web site.
     */
    static HttpdTomcatStdSiteManager<? extends TomcatCommon> getInstance(HttpdTomcatStdSite stdSite) throws IOException, SQLException {
        AOServConnector connector=AOServDaemon.getConnector();
        HttpdTomcatVersion htv=stdSite.getHttpdTomcatSite().getHttpdTomcatVersion();
        if(htv.isTomcat3_1(connector)) return new HttpdTomcatStdSiteManager_3_1(stdSite);
        if(htv.isTomcat3_2_4(connector)) return new HttpdTomcatStdSiteManager_3_2_4(stdSite);
        if(htv.isTomcat4_1_X(connector)) return new HttpdTomcatStdSiteManager_4_1_X(stdSite);
        if(htv.isTomcat5_5_X(connector)) return new HttpdTomcatStdSiteManager_5_5_X(stdSite);
        if(htv.isTomcat6_0_X(connector)) return new HttpdTomcatStdSiteManager_6_0_X(stdSite);
        throw new SQLException("Unsupported version of standard Tomcat: "+htv.getTechnologyVersion(connector).getVersion()+" on "+stdSite);
    }

    final protected HttpdTomcatStdSite tomcatStdSite;
    
    HttpdTomcatStdSiteManager(HttpdTomcatStdSite tomcatStdSite) {
        super(tomcatStdSite.getHttpdTomcatSite());
        this.tomcatStdSite = tomcatStdSite;
    }

    /**
     * Standard sites always have worker directly attached.
     */
    protected HttpdWorker getHttpdWorker() throws IOException, SQLException {
        AOServConnector conn = AOServDaemon.getConnector();
        List<HttpdWorker> workers = tomcatSite.getHttpdWorkers();

        // Prefer ajp13
        for(HttpdWorker hw : workers) {
            if(hw.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol().equals(HttpdJKProtocol.AJP13)) return hw;
        }
        // Try ajp12 next
        for(HttpdWorker hw : workers) {
            if(hw.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol().equals(HttpdJKProtocol.AJP12)) return hw;
        }
        throw new SQLException("Couldn't find either ajp13 or ajp12");
    }

    public UnixFile getPidFile() throws IOException, SQLException {
        return new UnixFile(
            HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration().getHttpdSitesDirectory()
            + "/"
            + httpdSite.getSiteName()
            + "/var/run/tomcat.pid"
        );
    }

    public boolean isStartable() throws IOException, SQLException {
        return httpdSite.getDisableLog()==null;
    }

    public String getStartStopScriptPath() throws IOException, SQLException {
        return
            HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration().getHttpdSitesDirectory()
            + "/"
            + httpdSite.getSiteName()
            + "/bin/tomcat"
        ;
    }

    public String getStartStopScriptUsername() throws IOException, SQLException {
        return httpdSite.getLinuxServerAccount().getLinuxAccount().getUsername().getUsername();
    }

    protected void flagNeedsRestart(Set<HttpdSite> sitesNeedingRestarted, Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted) {
        sitesNeedingRestarted.add(httpdSite);
    }

    protected void enableDisable(UnixFile siteDirectory) throws IOException, SQLException {
        UnixFile daemonUF = new UnixFile(siteDirectory, "daemon", false);
        UnixFile daemonSymlink = new UnixFile(daemonUF, "tomcat", false);
        if(httpdSite.getDisableLog()==null) {
            // Enabled
            if(!daemonSymlink.getStat().exists()) {
                daemonSymlink.symLink("../bin/tomcat").chown(
                    httpdSite.getLinuxServerAccount().getUID().getID(),
                    httpdSite.getLinuxServerGroup().getGID().getID()
                );
            }
        } else {
            // Disabled
            if(daemonSymlink.getStat().exists()) daemonSymlink.delete();
        }
    }

    /**
     * Builds the server.xml file.
     */
    protected abstract byte[] buildServerXml(UnixFile siteDirectory, String autoWarning) throws IOException, SQLException;

    protected boolean rebuildConfigFiles(UnixFile siteDirectory) throws IOException, SQLException {
        final String siteDir = siteDirectory.getPath();
        final Stat tempStat = new Stat();
        boolean needsRestart = false;
        String autoWarning=getAutoWarningXml();

        String confServerXML=siteDir+"/conf/server.xml";
        UnixFile confServerXMLFile=new UnixFile(confServerXML);
        if(!httpdSite.isManual() || !confServerXMLFile.getStat(tempStat).exists()) {
            // Only write to the actual file when missing or changed
            if(
                FileUtils.writeIfNeeded(
                    buildServerXml(siteDirectory, autoWarning),
                    null,
                    confServerXMLFile,
                    httpdSite.getLinuxServerAccount().getUID().getID(),
                    httpdSite.getLinuxServerGroup().getGID().getID(),
                    0660
                )
            ) {
                // Flag as needing restarted
                needsRestart = true;
            }
        } else {
            try {
                FileUtils.stripFilePrefix(
                    confServerXMLFile,
                    autoWarning,
                    tempStat
                );
            } catch(IOException err) {
                // Errors OK because this is done in manual mode and they might have symbolic linked stuff
            }
        }
        return needsRestart;
    }
}
