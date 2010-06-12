package com.aoindustries.aoserv.daemon.httpd;

/*
 * Copyright 2007-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.HttpdSharedTomcat;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.client.HttpdStaticSite;
import com.aoindustries.aoserv.daemon.util.FileUtils;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Manages HttpdStaticSite configurations.  These are the most stripped-down
 * form of website.  Should not allow any sort of server-side execution.
 * Perhaps we could sell these for $1/month?
 *
 * @author  AO Industries, Inc.
 */
public class HttpdStaticSiteManager extends HttpdSiteManager {

    /**
     * Gets the specific manager for one type of web site.
     */
    static HttpdStaticSiteManager getInstance(HttpdStaticSite staticSite) throws SQLException, IOException {
        return new HttpdStaticSiteManager(staticSite);
    }

    final protected HttpdStaticSite staticSite;
    
    private HttpdStaticSiteManager(HttpdStaticSite staticSite) throws SQLException, IOException {
        super(staticSite.getHttpdSite());
        this.staticSite = staticSite;
    }

    protected void buildSiteDirectory(UnixFile siteDirectory, Set<HttpdSite> sitesNeedingRestarted, Set<HttpdSharedTomcat> sharedTomcatsNeedingRestarted) throws IOException, SQLException {
        final Stat tempStat = new Stat();
        final boolean isAuto = !httpdSite.isManual();
        final int apacheUid = getApacheUid();
        final int uid = httpdSite.getLinuxServerAccount().getUID().getID();
        final int gid = httpdSite.getLinuxServerGroup().getGID().getID();

        // Create wwwDirectory if needed
        if(!siteDirectory.getStat(tempStat).exists()) {
            siteDirectory.mkdir(false, 0700);
            siteDirectory.getStat(tempStat);
        } else if(!tempStat.isDirectory()) throw new IOException("Not a directory: "+siteDirectory);

        // New if still owned by root
        final boolean isNew = tempStat.getUid() == UnixFile.ROOT_UID;

        // conf/
        if(isNew || isAuto) FileUtils.mkdir(new UnixFile(siteDirectory, "conf", false), 0775, uid, gid);
        // htdocs/
        UnixFile htdocsDirectory = new UnixFile(siteDirectory, "htdocs", false);
        if(isNew || isAuto) FileUtils.mkdir(htdocsDirectory, 0775, uid, gid);
        // htdocs/index.html
        if(isNew) createTestIndex(new UnixFile(htdocsDirectory, "index.html", false));

        // Complete, set permission and ownership
        siteDirectory.getStat(tempStat);
        if(tempStat.getMode()!=0770) siteDirectory.setMode(0770);
        if(tempStat.getUid()!=apacheUid || tempStat.getGid()!=gid) siteDirectory.chown(apacheUid, gid);
    }

    /**
     * No CGI.
     */
    protected boolean enableCgi() {
        return false;
    }

    /**
     * No PHP.
     */
    protected boolean enablePhp() {
        return false;
    }
    
    /**
     * No anonymous FTP directory.
     */
    public boolean enableAnonymousFtp() {
        return false;
    }

    public SortedMap<String,WebAppSettings> getWebapps() throws IOException, SQLException {
        SortedMap<String,WebAppSettings> webapps = new TreeMap<String,WebAppSettings>();
        webapps.put(
            "",
            new WebAppSettings(
                HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration().getHttpdSitesDirectory()+'/'+httpdSite.getSiteName()+"/htdocs",
                "AuthConfig Indexes Limit",
                "Indexes IncludesNOEXEC",
                enableCgi()
            )
        );
        return webapps;
    }
}
