
package com.aoindustries.aoserv.daemon.httpd;

/*
 * Copyright 2008-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServConnector;
import com.aoindustries.aoserv.client.AOServer;
import com.aoindustries.aoserv.client.HttpdBind;
import com.aoindustries.aoserv.client.HttpdServer;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.client.HttpdSiteAuthenticatedLocation;
import com.aoindustries.aoserv.client.HttpdSiteBind;
import com.aoindustries.aoserv.client.HttpdSiteURL;
import com.aoindustries.aoserv.client.HttpdWorker;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.LinuxGroup;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.LinuxServerGroup;
import com.aoindustries.aoserv.client.NetBind;
import com.aoindustries.aoserv.client.TechnologyVersion;
import com.aoindustries.aoserv.daemon.AOServDaemon;
import com.aoindustries.aoserv.daemon.LogFactory;
import com.aoindustries.aoserv.daemon.OperatingSystemConfiguration;
import com.aoindustries.aoserv.daemon.util.FileUtils;
import com.aoindustries.io.ChainWriter;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.logging.Level;

/**
 * Manages HttpdServer configurations and control.
 *
 * @author  AO Industries, Inc.
 */
public class HttpdServerManager {

    private HttpdServerManager() {}

    /**
     * The directory that all HTTPD configs are located in (/etc/httpd).
     */
    static final String CONFIG_DIRECTORY = "/etc/httpd";

    /**
     * The directory that HTTPD conf are located in (/etc/httpd/conf).
     */
    static final String CONF_DIRECTORY = CONFIG_DIRECTORY + "/conf";

    /**
     * The directory that individual host and bind configurations are in.
     */
    static final String CONF_HOSTS = CONF_DIRECTORY+"/hosts";

    /**
     * The init.d directory.
     */
    private static final String INIT_DIRECTORY = "/etc/rc.d/init.d";

    /**
     * Gets the workers#.properties file path.
     */
    private static String getWorkersFile(HttpdServer hs) {
        return CONF_DIRECTORY+"/workers"+hs.getNumber()+".properties";
    }

    /**
     * Gets the workers#.properties.new file path.
     */
    private static String getWorkersNewFile(HttpdServer hs) {
        return getWorkersFile(hs)+".new";
    }

    /**
     * Gets the httpd#.conf file path.
     */
    private static String getHttpdConfFile(HttpdServer hs) {
        return CONF_DIRECTORY+"/httpd"+hs.getNumber()+".conf";
    }

    /**
     * Gets the httpd#.conf.new file path.
     */
    private static String getHttpdConfNewFile(HttpdServer hs) {
        return getHttpdConfFile(hs)+".new";
    }

    /**
     * Only called by the already synchronized <code>HttpdManager.doRebuild()</code> method.
     */
    static void doRebuild(
        List<File> deleteFileList,
        Set<HttpdServer> serversNeedingReloaded
    ) throws IOException, SQLException {
        // Used below
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        AOServer aoServer = AOServDaemon.getThisAOServer();

        // Rebuild /etc/httpd/conf/hosts/ files
        doRebuildConfHosts(aoServer, bout, deleteFileList, serversNeedingReloaded);

        // Rebuild /etc/httpd/conf/ files
        doRebuildConf(aoServer, bout, serversNeedingReloaded);
        
        // Control the /etc/rc.d/init.d/httpd* files
        doRebuildInitScripts(aoServer, bout, deleteFileList, serversNeedingReloaded);

        // Other filesystem fixes related to logging
        fixFilesystem(deleteFileList);
    }
    
    /**
     * Rebuilds the files in /etc/httpd/conf/hosts/
     */
    private static void doRebuildConfHosts(
        AOServer aoServer,
        ByteArrayOutputStream bout,
        List<File> deleteFileList,
        Set<HttpdServer> serversNeedingReloaded
    ) throws IOException, SQLException {
        // Values used below
        Stat tempStat = new Stat();

        // The config directory should only contain files referenced in the database
        String[] list=new File(CONF_HOSTS).list();
        Set<String> extraFiles = new HashSet<String>(list.length*4/3+1);
        for(String filename : list) extraFiles.add(filename);

        // Iterate through each site
        for(HttpdSite httpdSite : aoServer.getHttpdSites()) {
            // Some values used below
            final String siteName = httpdSite.getSiteName();
            final HttpdSiteManager manager = HttpdSiteManager.getInstance(httpdSite);
            final LinuxServerAccount lsa = httpdSite.getLinuxServerAccount();
            final LinuxServerGroup lsg = httpdSite.getLinuxServerGroup();
            final int lsgGID = lsg.getGid().getID();
            final List<HttpdSiteBind> binds = httpdSite.getHttpdSiteBinds();

            // Remove from delete list
            extraFiles.remove(siteName);

            // The shared config part
            final UnixFile sharedFile = new UnixFile(CONF_HOSTS, siteName);
            if(!manager.httpdSite.isManual() || !sharedFile.getStat(tempStat).exists()) {
                if(
                    FileUtils.writeIfNeeded(
                        buildHttpdSiteSharedFile(manager, bout),
                        null,
                        sharedFile,
                        UnixFile.ROOT_UID,
                        lsgGID,
                        0640
                    )
                ) {
                    // File changed, all servers that use this site need restarted
                    for(HttpdSiteBind hsb : binds) serversNeedingReloaded.add(hsb.getHttpdBind().getHttpdServer());
                }
            }

            // Each of the binds
            for(HttpdSiteBind bind : binds) {
                // Some value used below
                final boolean isManual = bind.isManual();
                final boolean isDisabled = bind.isDisabled();
                final String predisableConfig = bind.getPredisableConfig();
                final HttpdBind httpdBind = bind.getHttpdBind();
                final NetBind nb = httpdBind.getNetBind();

                // Generate the filename
                final String bindFilename = siteName+'_'+nb.getIPAddress().getIPAddress()+'_'+nb.getPort().getPort();
                final UnixFile bindFile = new UnixFile(CONF_HOSTS, bindFilename);
                final boolean exists = bindFile.getStat(tempStat).exists();

                // Remove from delete list
                extraFiles.remove(bindFilename);

                // Will only be verified when not exists, auto mode, disabled, or predisabled config need to be restored
                if(
                    !exists                                 // Not exists
                    || !isManual                            // Auto mode
                    || isDisabled                           // Disabled
                    || predisableConfig!=null               // Predisabled config needs to be restored
                ) {
                    // Save manual config file for later restoration
                    if(exists && isManual && isDisabled && predisableConfig==null) {
                        bind.setPredisableConfig(FileUtils.readFileAsString(bindFile));
                    }
                    
                    // Restore/build the file
                    byte[] newContent;
                    if(isManual && !isDisabled && predisableConfig!=null) {
                        // Restore manual config values
                        newContent = predisableConfig.getBytes();
                    } else {
                        // Create auto config
                        newContent = buildHttpdSiteBindFile(
                            bind,
                            isDisabled ? HttpdSite.DISABLED : siteName,
                            bout
                        );
                    }
                    // Write only when missing or modified
                    if(
                        FileUtils.writeIfNeeded(
                            newContent,
                            null,
                            bindFile,
                            UnixFile.ROOT_UID,
                            lsgGID,
                            0640
                       )
                    ) {
                        // Reload server if the file is modified
                        serversNeedingReloaded.add(httpdBind.getHttpdServer());
                    }
                }
            }
        }

        // Mark files for deletion
        for(String filename : extraFiles) deleteFileList.add(new File(CONF_HOSTS, filename));
    }

    /**
     * Builds the contents for the shared part of a HttpdSite config.
     */
    private static byte[] buildHttpdSiteSharedFile(HttpdSiteManager manager, ByteArrayOutputStream bout) throws IOException, SQLException {
        final HttpdSite httpdSite = manager.httpdSite;
        final LinuxServerAccount lsa = httpdSite.getLinuxServerAccount();
        final LinuxServerGroup lsg = httpdSite.getLinuxServerGroup();

        // Build to a temporary buffer
        bout.reset();
        ChainWriter out = new ChainWriter(bout);
        try {
            out.print("    ServerAdmin ").print(httpdSite.getServerAdmin()).print("\n");

            // Enable CGI PHP option if the site supports CGI and PHP
            if(manager.enablePhp() && manager.enableCgi()) {
                out.print("\n"
                        + "    # Use CGI-based PHP when not using mod_php\n"
                        + "    <IfModule !sapi_apache2.c>\n"
                        + "        <IfModule !mod_php5.c>\n"
                        + "            Action php-script /cgi-bin/php\n"
                        + "            AddHandler php-script .php .php3 .php4\n"
                        + "        </IfModule>\n"
                        + "    </IfModule>\n");
            }

            // The CGI user info
            out.print("\n"
                    + "    # Use suexec when available\n"
                    + "    <IfModule mod_suexec.c>\n"
                    + "        SuexecUserGroup ").print(lsa.getLinuxAccount().getUsername()).print(' ').print(lsg.getLinuxGroup().getName()).print("\n"
                    + "    </IfModule>\n");

            // Protect against TRACE and TRACK
            if(manager.blockAllTraceAndTrackRequests()) {
                out.print("\n"
                        + "    # Protect dangerous request methods\n"
                        + "    <IfModule mod_rewrite.c>\n"
                        + "        RewriteEngine on\n"
                        + "        RewriteCond %{REQUEST_METHOD} ^(TRACE|TRACK)\n"
                        + "        RewriteRule .* - [F]\n"
                        + "    </IfModule>\n");
            }

            // Rejected URLs
            SortedSet<HttpdSiteManager.Location> rejectedLocations = manager.getRejectedLocations();
            if(!rejectedLocations.isEmpty()) {
                out.print("\n"
                        + "    # Rejected URL patterns\n");
                for(HttpdSiteManager.Location location : rejectedLocations) {
                    if(location.isRegularExpression()) {
                        out.print("    <LocationMatch \"").print(location.getLocation()).print("\">\n"
                                + "        Order deny,allow\n"
                                + "        Deny from All\n"
                                + "    </LocationMatch>\n"
                        );
                    } else {
                        out.print("    <Location \"").print(location.getLocation()).print("\">\n"
                                + "        Order deny,allow\n"
                                + "        Deny from All\n"
                                + "    </Location>\n"
                        );
                    }
                }
            }

            // Rewrite rules
            SortedMap<String,String> permanentRewrites = manager.getPermanentRewriteRules();
            if(!permanentRewrites.isEmpty()) {
                // Write the standard restricted URL patterns
                out.print("\n"
                        + "    # Rewrite rules\n"
                        + "    <IfModule mod_rewrite.c>\n"
                        + "        RewriteEngine on\n");
                for(Map.Entry<String,String> entry : permanentRewrites.entrySet()) {
                    out.print("        RewriteRule ").print(entry.getKey()).print(' ').print(entry.getValue()).print(" [L,R=permanent]\n");
                }
                out.print("    </IfModule>\n");
            }

            // Write the authenticated locations
            List<HttpdSiteAuthenticatedLocation> hsals = httpdSite.getHttpdSiteAuthenticatedLocations();
            if(!hsals.isEmpty()) {
                out.print("\n"
                        + "    # Authenticated Locations\n");
                for(HttpdSiteAuthenticatedLocation hsal : hsals) {
                    out.print("    <").print(hsal.getIsRegularExpression()?"LocationMatch":"Location").print(" \"").print(hsal.getPath()).print("\">\n");
                    if(hsal.getAuthUserFile().length()>0 || hsal.getAuthGroupFile().length()>0) out.print("        AuthType Basic\n");
                    if(hsal.getAuthName().length()>0) out.print("        AuthName \"").print(hsal.getAuthName()).print("\"\n");
                    if(hsal.getAuthUserFile().length()>0) out.print("        AuthUserFile \"").print(hsal.getAuthUserFile()).print("\"\n");
                    if(hsal.getAuthGroupFile().length()>0) out.print("        AuthGroupFile \"").print(hsal.getAuthGroupFile()).print("\"\n");
                    if(hsal.getRequire().length()>0) out.print("        require ").print(hsal.getRequire()).print('\n');
                    out.print("    </").print(hsal.getIsRegularExpression()?"LocationMatch":"Location").print(">\n");
                }
            }

            // Error if no root webapp found
            boolean foundRoot = false;
            SortedMap<String,HttpdSiteManager.WebAppSettings> webapps = manager.getWebapps();
            for(Map.Entry<String,HttpdSiteManager.WebAppSettings> entry : webapps.entrySet()) {
                String path = entry.getKey();
                HttpdSiteManager.WebAppSettings settings = entry.getValue();
                String docBase = settings.getDocBase();

                if(path.length()==0) {
                    foundRoot = true;
                    // DocumentRoot
                    out.print("\n"
                            + "    # Set up the default webapp\n"
                            + "    DocumentRoot \"").print(docBase).print("\"\n"
                            + "    <Directory \"").print(docBase).print("\">\n"
                            + "        Allow from All\n"
                            + "        AllowOverride ").print(settings.getAllowOverride()).print("\n"
                            + "        Order allow,deny\n"
                            + "        Options ").print(settings.getOptions()).print("\n"
                            + "    </Directory>\n");
                } else {
                    // Is webapp/alias
                    out.print("\n"
                            + "    # Set up the ").print(path).print(" webapp\n"
                            + "    Alias \"").print(path).print("/\" \"").print(docBase).print("/\"\n"
                            + "    AliasMatch \"^").print(path).print("$\" \"").print(docBase).print("\"\n"
                            + "    <Directory \"").print(docBase).print("\">\n"
                            + "        Allow from All\n"
                            + "        AllowOverride ").print(settings.getAllowOverride()).print("\n"
                            + "        Order allow,deny\n"
                            + "        Options ").print(settings.getOptions()).print("\n"
                            + "    </Directory>\n");
                }
                if(settings.enableCgi()) {
                    if(!manager.enableCgi()) throw new SQLException("Unable to enable webapp CGI when site has CGI disabled");
                    out.print("    <Directory \"").print(docBase).print("/cgi-bin\">\n"
                            + "        <IfModule mod_ssl.c>\n"
                            + "            SSLOptions +StdEnvVars\n"
                            + "        </IfModule>\n"
                            + "        Options ExecCGI\n"
                            + "        SetHandler cgi-script\n"
                            + "    </Directory>\n");
                    /*
                    out.print("    ScriptAlias \"").print(path).print("/cgi-bin/\" \"").print(docBase).print("/cgi-bin/\"\n"
                            + "    <Directory \"").print(docBase).print("/cgi-bin\">\n"
                            + "        Options ExecCGI\n"
                            + "        <IfModule mod_ssl.c>\n"
                            + "            SSLOptions +StdEnvVars\n"
                            + "        </IfModule>\n"
                            + "        Allow from All\n"
                            + "        Order allow,deny\n"
                            + "    </Directory>\n");*/
                }
            }
            if(!foundRoot) throw new SQLException("No DocumentRoot found");

            // Write any JkMount and JkUnmount directives
            SortedSet<HttpdSiteManager.JkSetting> jkSettings = manager.getJkSettings();
            if(!jkSettings.isEmpty()) {
                out.print("\n"
                        + "    # Request patterns mapped through mod_jk\n"
                        + "    <IfModule mod_jk.c>\n");
                for(HttpdSiteManager.JkSetting setting : jkSettings) {
                    out
                        .print("        ")
                        .print(setting.isMount() ? "JkMount" : "JkUnMount")
                        .print(' ')
                        .print(setting.getPath())
                        .print(' ')
                        .print(setting.getJkCode())
                        .print('\n');
                }
                out.print("\n"
                        + "        # Remove jsessionid for non-jk requests\n"
                        + "        <IfModule mod_rewrite.c>\n"
                        + "            RewriteEngine On\n"
                        + "            RewriteRule ^(.*);jsessionid=.*$ $1\n"
                        + "        </IfModule>\n"
                        + "    </IfModule>\n");
            }
        } finally {
            out.close();
        }
        return bout.toByteArray();
    }

    /**
     * Rebuilds the files in /etc/httpd/conf/
     * <ul>
     *   <li>/etc/httpd/conf/httpd#.conf</li>
     *   <li>/etc/httpd/conf/workers#.properties</li>
     * </ul>
     */
    private static void doRebuildConf(AOServer aoServer, ByteArrayOutputStream bout, Set<HttpdServer> serversNeedingReloaded) throws IOException, SQLException {
        // Rebuild per-server files
        for(HttpdServer hs : aoServer.getHttpdServers()) {
            // Rebuild the httpd.conf file
            if(
                FileUtils.writeIfNeeded(
                    buildHttpdConf(hs, bout),
                    new UnixFile(getHttpdConfNewFile(hs)),
                    new UnixFile(getHttpdConfFile(hs)),
                    UnixFile.ROOT_UID,
                    UnixFile.ROOT_GID,
                    0644
                )
            ) {
                serversNeedingReloaded.add(hs);
            }

            // Rebuild the workers.properties file
            if(
                FileUtils.writeIfNeeded(
                    buildWorkersFile(hs, bout),
                    new UnixFile(getWorkersNewFile(hs)),
                    new UnixFile(getWorkersFile(hs)),
                    UnixFile.ROOT_UID,
                    UnixFile.ROOT_GID,
                    0644
                )
            ) {
                serversNeedingReloaded.add(hs);
            }
        }
    }

    /**
     * Builds the httpd#.conf file for CentOS 5
     */
    private static byte[] buildHttpdConfCentOs5(HttpdServer hs, ByteArrayOutputStream bout) throws IOException, SQLException {
        final HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
        if(osConfig!=HttpdOperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64) throw new AssertionError("This method is for CentOS 5 only");
        final int serverNum = hs.getNumber();
        bout.reset();
        ChainWriter out = new ChainWriter(bout);
        try {
            LinuxServerAccount lsa=hs.getLinuxServerAccount();
            boolean isEnabled=!lsa.isDisabled();
            // The version of PHP module to run
            TechnologyVersion phpVersion=hs.getModPhpVersion();
            out.print("ServerRoot \""+CONFIG_DIRECTORY+"\"\n"
                    + "Include conf/modules_conf/core\n"
                    + "PidFile /var/run/httpd").print(serverNum).print(".pid\n"
                    + "Timeout ").print(hs.getTimeOut()).print("\n"
                    + "CoreDumpDirectory /var/log/httpd/httpd").print(serverNum).print("\n"
                    + "LockFile /var/log/httpd/httpd").print(serverNum).print("/accept.lock\n"
                    + "\n"
                    + "Include conf/modules_conf/prefork\n"
                    + "Include conf/modules_conf/worker\n"
                    + "\n"
                    + "LoadModule auth_basic_module modules/mod_auth_basic.so\n"
                    + "#LoadModule auth_digest_module modules/mod_auth_digest.so\n"
                    + "LoadModule authn_file_module modules/mod_authn_file.so\n"
                    + "#LoadModule authn_alias_module modules/mod_authn_alias.so\n"
                    + "#LoadModule authn_anon_module modules/mod_authn_anon.so\n"
                    + "#LoadModule authn_dbm_module modules/mod_authn_dbm.so\n"
                    + "#LoadModule authn_default_module modules/mod_authn_default.so\n"
                    + "LoadModule authz_host_module modules/mod_authz_host.so\n"
                    + "LoadModule authz_user_module modules/mod_authz_user.so\n"
                    + "#LoadModule authz_owner_module modules/mod_authz_owner.so\n"
                    + "LoadModule authz_groupfile_module modules/mod_authz_groupfile.so\n"
                    + "#LoadModule authz_dbm_module modules/mod_authz_dbm.so\n"
                    + "#LoadModule authz_default_module modules/mod_authz_default.so\n"
                    + "#LoadModule ldap_module modules/mod_ldap.so\n"
                    + "#LoadModule authnz_ldap_module modules/mod_authnz_ldap.so\n"
                    + "LoadModule include_module modules/mod_include.so\n"
                    + "LoadModule log_config_module modules/mod_log_config.so\n"
                    + "#LoadModule logio_module modules/mod_logio.so\n"
                    + "LoadModule env_module modules/mod_env.so\n"
                    + "#LoadModule ext_filter_module modules/mod_ext_filter.so\n"
                    + "LoadModule mime_magic_module modules/mod_mime_magic.so\n"
                    + "LoadModule expires_module modules/mod_expires.so\n"
                    + "LoadModule deflate_module modules/mod_deflate.so\n"
                    + "LoadModule headers_module modules/mod_headers.so\n"
                    + "#LoadModule usertrack_module modules/mod_usertrack.so\n"
                    + "LoadModule setenvif_module modules/mod_setenvif.so\n"
                    + "LoadModule mime_module modules/mod_mime.so\n"
                    + "#LoadModule dav_module modules/mod_dav.so\n"
                    + "LoadModule status_module modules/mod_status.so\n"
                    + "LoadModule autoindex_module modules/mod_autoindex.so\n"
                    + "#LoadModule info_module modules/mod_info.so\n"
                    + "#LoadModule dav_fs_module modules/mod_dav_fs.so\n"
                    + "#LoadModule vhost_alias_module modules/mod_vhost_alias.so\n"
                    + "LoadModule negotiation_module modules/mod_negotiation.so\n"
                    + "LoadModule dir_module modules/mod_dir.so\n"
                    + "LoadModule imagemap_module modules/mod_imagemap.so\n"
                    + "LoadModule actions_module modules/mod_actions.so\n"
                    + "#LoadModule speling_module modules/mod_speling.so\n"
                    + "#LoadModule userdir_module modules/mod_userdir.so\n"
                    + "LoadModule alias_module modules/mod_alias.so\n"
                    + "LoadModule rewrite_module modules/mod_rewrite.so\n"
                    + "LoadModule proxy_module modules/mod_proxy.so\n"
                    + "#LoadModule proxy_balancer_module modules/mod_proxy_balancer.so\n"
                    + "#LoadModule proxy_ftp_module modules/mod_proxy_ftp.so\n"
                    + "LoadModule proxy_http_module modules/mod_proxy_http.so\n"
                    + "#LoadModule proxy_connect_module modules/mod_proxy_connect.so\n"
                    + "#LoadModule cache_module modules/mod_cache.so\n");
            if(hs.useSuexec()) out.print("LoadModule suexec_module modules/mod_suexec.so\n");
            else out.print("#LoadModule suexec_module modules/mod_suexec.so\n");
            out.print("#LoadModule disk_cache_module modules/mod_disk_cache.so\n"
                    + "#LoadModule file_cache_module modules/mod_file_cache.so\n"
                    + "#LoadModule mem_cache_module modules/mod_mem_cache.so\n"
                    + "LoadModule cgi_module modules/mod_cgi.so\n"
                    + "#LoadModule cern_meta_module modules/mod_cern_meta.so\n"
                    + "#LoadModule asis_module modules/mod_asis.so\n"
                    + "LoadModule jk_module modules/mod_jk-1.2.27.so\n"
                    + "LoadModule ssl_module modules/mod_ssl.so\n");
            if(isEnabled && phpVersion!=null) {
                String version = phpVersion.getVersion();
                String phpMajorVersion = getMajorPhpVersion(version);
                out.print("\n"
                        + "# Enable mod_php\n"
                        + "LoadModule php").print(phpMajorVersion).print("_module /opt/php-").print(phpMajorVersion).print("-i686/lib/apache/").print(getPhpLib(phpVersion)).print("\n"
                        + "AddType application/x-httpd-php .php4 .php3 .phtml .php\n"
                        + "AddType application/x-httpd-php-source .phps\n");
            }
            out.print("\n"
                    + "Include conf/modules_conf/mod_ident\n"
                    + "Include conf/modules_conf/mod_log_config\n"
                    + "Include conf/modules_conf/mod_mime_magic\n"
                    + "Include conf/modules_conf/mod_setenvif\n"
                    + "Include conf/modules_conf/mod_proxy\n"
                    + "Include conf/modules_conf/mod_mime\n"
                    + "Include conf/modules_conf/mod_dav\n"
                    + "Include conf/modules_conf/mod_status\n"
                    + "Include conf/modules_conf/mod_autoindex\n"
                    + "Include conf/modules_conf/mod_negotiation\n"
                    + "Include conf/modules_conf/mod_dir\n"
                    + "Include conf/modules_conf/mod_userdir\n"
                    + "Include conf/modules_conf/mod_ssl\n"
                    + "Include conf/modules_conf/mod_jk\n"
                    + "\n"
                    + "ServerAdmin root@").print(hs.getAOServer().getHostname()).print("\n"
                    + "\n"
                    + "SSLSessionCache shmcb:/var/cache/httpd/mod_ssl/ssl_scache").print(serverNum).print("(512000)\n"
                    + "\n");
            // Use apache if the account is disabled
            if(isEnabled) {
                out.print("User ").print(lsa.getLinuxAccount().getUsername().getUsername()).print("\n"
                        + "Group ").print(hs.getLinuxServerGroup().getLinuxGroup().getName()).print("\n");
            } else {
                out.print("User "+LinuxAccount.APACHE+"\n"
                        + "Group "+LinuxGroup.APACHE+"\n");
            }
            out.print("\n"
                    + "ServerName ").print(hs.getAOServer().getHostname()).print("\n"
                    + "\n"
                    + "ErrorLog /var/log/httpd/httpd").print(serverNum).print("/error_log\n"
                    + "CustomLog /var/log/httpd/httpd").print(serverNum).print("/access_log combined\n"
                    + "\n"
                    + "<IfModule mod_dav_fs.c>\n"
                    + "    DAVLockDB /var/lib/dav").print(serverNum).print("/lockdb\n"
                    + "</IfModule>\n"
                    + "\n"
                    + "<IfModule mod_jk.c>\n"
                    + "    JkWorkersFile /etc/httpd/conf/workers").print(serverNum).print(".properties\n"
                    + "    JkLogFile /var/log/httpd/httpd").print(serverNum).print("/mod_jk.log\n"
                    + "    JkShmFile /var/log/httpd/httpd").print(serverNum).print("/jk-runtime-status\n"
                    + "</IfModule>\n"
                    + "\n"
            );

            // List of binds
            for(HttpdBind hb : hs.getHttpdBinds()) {
                NetBind nb=hb.getNetBind();
                String ip=nb.getIPAddress().getIPAddress();
                int port=nb.getPort().getPort();
                out.print("Listen ").print(ip).print(':').print(port).print("\n"
                        + "NameVirtualHost ").print(ip).print(':').print(port).print('\n');
            }

            // The list of sites to include
            List<HttpdSite> sites=hs.getHttpdSites();
            for(int d=0;d<2;d++) {
                boolean listFirst=d==0;
                out.print("\n");
                for(HttpdSite site : sites) {
                    if(site.listFirst()==listFirst) {
                        for(HttpdSiteBind bind : site.getHttpdSiteBinds(hs)) {
                            NetBind nb=bind.getHttpdBind().getNetBind();
                            String ipAddress=nb.getIPAddress().getIPAddress();
                            int port=nb.getPort().getPort();
                            out.print("Include conf/hosts/").print(site.getSiteName()).print('_').print(ipAddress).print('_').print(port).print('\n');
                        }
                    }
                }
            }
        } finally {
            out.close();
        }
        return bout.toByteArray();
    }

    /**
     * Builds the httpd#.conf file for RedHat ES 4
     */
    private static byte[] buildHttpdConfRedHatEs4(HttpdServer hs, ByteArrayOutputStream bout) throws IOException, SQLException {
        final HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
        if(osConfig!=HttpdOperatingSystemConfiguration.REDHAT_ES_4_X86_64) throw new AssertionError("This method is for RedHat ES 4 only");
        final int serverNum = hs.getNumber();
        bout.reset();
        ChainWriter out = new ChainWriter(bout);
        try {
            LinuxServerAccount lsa=hs.getLinuxServerAccount();
            boolean isEnabled=!lsa.isDisabled();
            // The version of PHP module to run
            TechnologyVersion phpVersion=hs.getModPhpVersion();
            out.print("ServerRoot \""+CONFIG_DIRECTORY+"\"\n"
                    + "Include conf/modules_conf/core\n"
                    + "PidFile /var/run/httpd").print(serverNum).print(".pid\n"
                    + "Timeout ").print(hs.getTimeOut()).print("\n"
                    + "CoreDumpDirectory /var/log/httpd").print(serverNum).print("\n"
                    + "LockFile /var/log/httpd").print(serverNum).print("/accept.lock\n"
                    + "\n"
                    + "Include conf/modules_conf/prefork\n"
                    + "Include conf/modules_conf/worker\n"
                    + "\n"
                    + "LoadModule access_module modules/mod_access.so\n"
                    + "LoadModule auth_module modules/mod_auth.so\n"
                    + "# LoadModule auth_anon_module modules/mod_auth_anon.so\n"
                    + "# LoadModule auth_dbm_module modules/mod_auth_dbm.so\n"
                    + "# LoadModule auth_digest_module modules/mod_auth_digest.so\n"
                    + "# LoadModule ldap_module modules/mod_ldap.so\n"
                    + "# LoadModule auth_ldap_module modules/mod_auth_ldap.so\n"
                    + "LoadModule include_module modules/mod_include.so\n"
                    + "LoadModule log_config_module modules/mod_log_config.so\n"
                    + "LoadModule env_module modules/mod_env.so\n"
                    + "LoadModule mime_magic_module modules/mod_mime_magic.so\n"
                    + "# LoadModule cern_meta_module modules/mod_cern_meta.so\n"
                    + "LoadModule expires_module modules/mod_expires.so\n"
                    + "LoadModule deflate_module modules/mod_deflate.so\n"
                    + "LoadModule headers_module modules/mod_headers.so\n"
                    + "# LoadModule usertrack_module modules/mod_usertrack.so\n"
                    + "LoadModule setenvif_module modules/mod_setenvif.so\n"
                    + "LoadModule mime_module modules/mod_mime.so\n"
                    + "# LoadModule dav_module modules/mod_dav.so\n"
                    + "# LoadModule status_module modules/mod_status.so\n"
                    + "LoadModule autoindex_module modules/mod_autoindex.so\n"
                    + "LoadModule asis_module modules/mod_asis.so\n"
                    + "# LoadModule info_module modules/mod_info.so\n"
                    + "# LoadModule dav_fs_module modules/mod_dav_fs.so\n"
                    + "# LoadModule vhost_alias_module modules/mod_vhost_alias.so\n"
                    + "LoadModule negotiation_module modules/mod_negotiation.so\n"
                    + "LoadModule dir_module modules/mod_dir.so\n"
                    + "LoadModule imap_module modules/mod_imap.so\n"
                    + "LoadModule actions_module modules/mod_actions.so\n"
                    + "# LoadModule speling_module modules/mod_speling.so\n"
                    + "# LoadModule userdir_module modules/mod_userdir.so\n"
                    + "LoadModule alias_module modules/mod_alias.so\n"
                    + "LoadModule rewrite_module modules/mod_rewrite.so\n"
                    + "LoadModule proxy_module modules/mod_proxy.so\n"
                    + "# LoadModule proxy_ftp_module modules/mod_proxy_ftp.so\n"
                    + "LoadModule proxy_http_module modules/mod_proxy_http.so\n"
                    + "# LoadModule proxy_connect_module modules/mod_proxy_connect.so\n"
                    + "# LoadModule cache_module modules/mod_cache.so\n");
            if(hs.useSuexec()) out.print("LoadModule suexec_module modules/mod_suexec.so\n");
            if(isEnabled && phpVersion!=null) {
                String version = phpVersion.getVersion();
                String phpMajorVersion = getMajorPhpVersion(version);
                out.print("\n"
                        + "# Enable mod_php\n"
                        + "LoadModule php").print(phpMajorVersion).print("_module /opt/php-").print(phpMajorVersion).print("/lib/apache/").print(getPhpLib(phpVersion)).print("\n"
                        + "AddType application/x-httpd-php .php4 .php3 .phtml .php\n"
                        + "AddType application/x-httpd-php-source .phps\n");
            }
            out.print("# LoadModule disk_cache_module modules/mod_disk_cache.so\n"
                    + "# LoadModule file_cache_module modules/mod_file_cache.so\n"
                    + "# LoadModule mem_cache_module modules/mod_mem_cache.so\n"
                    + "LoadModule cgi_module modules/mod_cgi.so\n"
                    + "LoadModule ssl_module modules/mod_ssl.so\n"
                    + "LoadModule jk_module modules/mod_jk-apache-2.0.52-linux-x86_64.so\n"
                    + "\n"
                    + "Include conf/modules_conf/mod_log_config\n"
                    + "Include conf/modules_conf/mod_mime_magic\n"
                    + "Include conf/modules_conf/mod_setenvif\n"
                    + "Include conf/modules_conf/mod_mime\n"
                    + "Include conf/modules_conf/mod_status\n"
                    + "Include conf/modules_conf/mod_autoindex\n"
                    + "Include conf/modules_conf/mod_negotiation\n"
                    + "Include conf/modules_conf/mod_dir\n"
                    + "Include conf/modules_conf/mod_userdir\n"
                    + "Include conf/modules_conf/mod_proxy\n"
                    + "Include conf/modules_conf/mod_ssl\n"
                    + "Include conf/modules_conf/mod_jk\n"
                    + "\n"
                    + "SSLSessionCache shmcb:/var/cache/mod_ssl/scache").print(serverNum).print("(512000)\n"
                    + "\n");
            // Use apache if the account is disabled
            if(isEnabled) {
                out.print("User ").print(lsa.getLinuxAccount().getUsername().getUsername()).print("\n"
                        + "Group ").print(hs.getLinuxServerGroup().getLinuxGroup().getName()).print("\n");
            } else {
                out.print("User "+LinuxAccount.APACHE+"\n"
                        + "Group "+LinuxGroup.APACHE+"\n");
            }
            out.print("\n"
                    + "ServerName ").print(hs.getAOServer().getHostname()).print("\n"
                    + "\n"
                    + "ErrorLog /var/log/httpd").print(serverNum).print("/error_log\n"
                    + "CustomLog /var/log/httpd").print(serverNum).print("/access_log combined\n"
                    + "\n"
                    + "<IfModule mod_dav_fs.c>\n"
                    + "    DAVLockDB /var/lib/dav").print(serverNum).print("/lockdb\n"
                    + "</IfModule>\n"
                    + "\n"
                    + "<IfModule mod_jk.c>\n"
                    + "    JkWorkersFile /etc/httpd/conf/workers").print(serverNum).print(".properties\n"
                    + "    JkLogFile /var/log/httpd").print(serverNum).print("/mod_jk.log\n"
                    + "</IfModule>\n"
                    + "\n"
            );

            // List of binds
            for(HttpdBind hb : hs.getHttpdBinds()) {
                NetBind nb=hb.getNetBind();
                String ip=nb.getIPAddress().getIPAddress();
                int port=nb.getPort().getPort();
                out.print("Listen ").print(ip).print(':').print(port).print("\n"
                        + "NameVirtualHost ").print(ip).print(':').print(port).print('\n');
            }

            // The list of sites to include
            List<HttpdSite> sites=hs.getHttpdSites();
            for(int d=0;d<2;d++) {
                boolean listFirst=d==0;
                out.print("\n");
                for(HttpdSite site : sites) {
                    if(site.listFirst()==listFirst) {
                        for(HttpdSiteBind bind : site.getHttpdSiteBinds(hs)) {
                            NetBind nb=bind.getHttpdBind().getNetBind();
                            String ipAddress=nb.getIPAddress().getIPAddress();
                            int port=nb.getPort().getPort();
                            out.print("Include conf/hosts/").print(site.getSiteName()).print('_').print(ipAddress).print('_').print(port).print('\n');
                        }
                    }
                }
            }
        } finally {
            out.close();
        }
        return bout.toByteArray();
    }

    /**
     * Builds the httpd#.conf file for Mandriva 2006.0
     */
    private static byte[] buildHttpdConfMandriva2006(HttpdServer hs, ByteArrayOutputStream bout) throws IOException, SQLException {
        final HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
        if(osConfig!=HttpdOperatingSystemConfiguration.MANDRIVA_2006_0_I586) throw new AssertionError("This method is for Mandriva 2006.0 only");
        final int serverNum = hs.getNumber();
        bout.reset();
        ChainWriter out = new ChainWriter(bout);
        try {
            LinuxServerAccount lsa=hs.getLinuxServerAccount();
            boolean isEnabled=!lsa.isDisabled();
            // The version of PHP module to run
            TechnologyVersion phpVersion=hs.getModPhpVersion();
            out.print("ServerRoot \""+CONFIG_DIRECTORY+"\"\n"
                    + "Include conf/modules_conf/core\n"
                    + "PidFile /var/run/httpd").print(serverNum).print(".pid\n"
                    + "Timeout ").print(hs.getTimeOut()).print("\n"
                    + "CoreDumpDirectory /var/log/httpd").print(serverNum).print("\n"
                    + "LockFile /var/log/httpd").print(serverNum).print("/accept.lock\n"
                    + "\n"
                    + "Include conf/modules_conf/prefork\n"
                    + "Include conf/modules_conf/worker\n"
                    + "\n"
                    + "LoadModule access_module modules/mod_access.so\n"
                    + "LoadModule auth_module modules/mod_auth.so\n"
                    + "# LoadModule auth_anon_module modules/mod_auth_anon.so\n"
                    + "# LoadModule auth_dbm_module modules/mod_auth_dbm.so\n"
                    + "# LoadModule auth_digest_module modules/mod_auth_digest.so\n"
                    + "# LoadModule file_cache_module modules/mod_file_cache.so\n"
                    + "# LoadModule charset_lite_module modules/mod_charset_lite.so\n"
                    + "# LoadModule cache_module modules/mod_cache.so\n"
                    + "# LoadModule disk_cache_module modules/mod_disk_cache.so\n"
                    + "# LoadModule mem_cache_module modules/mod_mem_cache.so\n"
                    + "# LoadModule case_filter_module modules/mod_case_filter.so\n"
                    + "# LoadModule case_filter_in_module modules/mod_case_filter_in.so\n"
                    + "# LoadModule dumpio_module modules/mod_dumpio.so\n"
                    + "# LoadModule ldap_module modules/mod_ldap.so\n"
                    + "# LoadModule auth_ldap_module modules/mod_auth_ldap.so\n"
                    + "# LoadModule ext_filter_module modules/mod_ext_filter.so\n"
                    + "LoadModule include_module modules/mod_include.so\n"
                    + "LoadModule deflate_module modules/mod_deflate.so\n"
                    + "LoadModule log_config_module modules/mod_log_config.so\n"
                    + "# LoadModule log_forensic_module modules/mod_log_forensic.so\n"
                    + "# LoadModule logio_module modules/mod_logio.so\n"
                    + "LoadModule env_module modules/mod_env.so\n"
                    + "LoadModule mime_magic_module modules/mod_mime_magic.so\n"
                    + "# LoadModule cern_meta_module modules/mod_cern_meta.so\n"
                    + "LoadModule expires_module modules/mod_expires.so\n"
                    + "LoadModule headers_module modules/mod_headers.so\n"
                    + "# LoadModule usertrack_module modules/mod_usertrack.so\n"
                    + "# LoadModule unique_id_module modules/mod_unique_id.so\n"
                    + "LoadModule setenvif_module modules/mod_setenvif.so\n"
                    + "LoadModule proxy_module modules/mod_proxy.so\n"
                    + "# LoadModule proxy_connect_module modules/mod_proxy_connect.so\n"
                    + "# LoadModule proxy_ftp_module modules/mod_proxy_ftp.so\n"
                    + "LoadModule proxy_http_module modules/mod_proxy_http.so\n"
                    + "LoadModule mime_module modules/mod_mime.so\n"
                    + "# LoadModule dav_module modules/mod_dav.so\n"
                    + "LoadModule status_module modules/mod_status.so\n"
                    + "LoadModule autoindex_module modules/mod_autoindex.so\n"
                    + "LoadModule asis_module modules/mod_asis.so\n"
                    + "# LoadModule info_module modules/mod_info.so\n"
                    + "LoadModule cgi_module modules/mod_cgi.so\n"
                    + "# LoadModule cgid_module modules/mod_cgid.so\n"
                    + "# LoadModule dav_fs_module modules/mod_dav_fs.so\n"
                    + "# LoadModule vhost_alias_module modules/mod_vhost_alias.so\n"
                    + "LoadModule negotiation_module modules/mod_negotiation.so\n"
                    + "LoadModule dir_module modules/mod_dir.so\n"
                    + "LoadModule imap_module modules/mod_imap.so\n"
                    + "LoadModule actions_module modules/mod_actions.so\n"
                    + "# LoadModule speling_module modules/mod_speling.so\n"
                    + "# LoadModule userdir_module modules/mod_userdir.so\n"
                    + "LoadModule alias_module modules/mod_alias.so\n"
                    + "LoadModule rewrite_module modules/mod_rewrite.so\n"
                    + "LoadModule jk_module modules/mod_jk-apache-2.0.49-linux-i686.so\n"
                    + "LoadModule ssl_module extramodules/mod_ssl.so\n");
            if(hs.useSuexec()) out.print("LoadModule suexec_module extramodules/mod_suexec.so\n");
            if(isEnabled && phpVersion!=null) {
                String version = phpVersion.getVersion();
                String phpMajorVersion = getMajorPhpVersion(version);
                out.print("\n"
                        + "# Enable mod_php\n"
                        + "LoadModule php").print(phpMajorVersion).print("_module /usr/php/").print(phpMajorVersion).print("/lib/apache/").print(getPhpLib(phpVersion)).print("\n"
                        + "AddType application/x-httpd-php .php4 .php3 .phtml .php\n"
                        + "AddType application/x-httpd-php-source .phps\n");
            }
            out.print("\n"
                    + "Include conf/modules_conf/mod_log_config\n"
                    + "Include conf/modules_conf/mod_mime_magic\n"
                    + "Include conf/modules_conf/mod_setenvif\n"
                    + "Include conf/modules_conf/mod_proxy\n"
                    + "Include conf/modules_conf/mod_mime\n"
                    + "Include conf/modules_conf/mod_dav\n"
                    + "Include conf/modules_conf/mod_status\n"
                    + "Include conf/modules_conf/mod_autoindex\n"
                    + "Include conf/modules_conf/mod_negotiation\n"
                    + "Include conf/modules_conf/mod_dir\n"
                    + "Include conf/modules_conf/mod_userdir\n"
                    + "Include conf/modules_conf/mod_ssl\n"
                    + "Include conf/modules_conf/mod_jk\n"
                    + "\n"
                    + "SSLSessionCache shmcb:/var/cache/httpd/mod_ssl/ssl_scache").print(serverNum).print("(512000)\n"
                    + "\n");
            // Use apache if the account is disabled
            if(isEnabled) {
                out.print("User ").print(lsa.getLinuxAccount().getUsername().getUsername()).print("\n"
                        + "Group ").print(hs.getLinuxServerGroup().getLinuxGroup().getName()).print("\n");
            } else {
                out.print("User "+LinuxAccount.APACHE+"\n"
                        + "Group "+LinuxGroup.APACHE+"\n");
            }
            out.print("\n"
                    + "ServerName ").print(hs.getAOServer().getHostname()).print("\n"
                    + "\n"
                    + "ErrorLog /var/log/httpd").print(serverNum).print("/error_log\n"
                    + "CustomLog /var/log/httpd").print(serverNum).print("/access_log combined\n"
                    + "\n"
                    + "<IfModule mod_dav_fs.c>\n"
                    + "    DAVLockDB /var/lib/dav").print(serverNum).print("/lockdb\n"
                    + "</IfModule>\n"
                    + "\n"
                    + "<IfModule mod_jk.c>\n"
                    + "    JkWorkersFile /etc/httpd/conf/workers").print(serverNum).print(".properties\n"
                    + "    JkLogFile /var/log/httpd").print(serverNum).print("/mod_jk.log\n"
                    + "</IfModule>\n"
                    + "\n"
                    + "Include conf/fileprotector.conf\n"
                    + "\n"
            );

            // List of binds
            for(HttpdBind hb : hs.getHttpdBinds()) {
                NetBind nb=hb.getNetBind();
                String ip=nb.getIPAddress().getIPAddress();
                int port=nb.getPort().getPort();
                out.print("Listen ").print(ip).print(':').print(port).print("\n"
                        + "NameVirtualHost ").print(ip).print(':').print(port).print('\n');
            }

            // The list of sites to include
            List<HttpdSite> sites=hs.getHttpdSites();
            for(int d=0;d<2;d++) {
                boolean listFirst=d==0;
                out.print("\n");
                for(HttpdSite site : sites) {
                    if(site.listFirst()==listFirst) {
                        for(HttpdSiteBind bind : site.getHttpdSiteBinds(hs)) {
                            NetBind nb=bind.getHttpdBind().getNetBind();
                            String ipAddress=nb.getIPAddress().getIPAddress();
                            int port=nb.getPort().getPort();
                            out.print("Include conf/hosts/").print(site.getSiteName()).print('_').print(ipAddress).print('_').print(port).print('\n');
                        }
                    }
                }
            }
        } finally {
            out.close();
        }
        return bout.toByteArray();
    }

    /**
     * Builds the httpd#.conf file contents for the provided HttpdServer.
     */
    private static byte[] buildHttpdConf(HttpdServer hs, ByteArrayOutputStream bout) throws IOException, SQLException {
        HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
        switch(osConfig) {
            case MANDRIVA_2006_0_I586     : return buildHttpdConfMandriva2006(hs, bout);
            case REDHAT_ES_4_X86_64       : return buildHttpdConfRedHatEs4(hs, bout);
            case CENTOS_5_I686_AND_X86_64 : return buildHttpdConfCentOs5(hs, bout);
            default                       : throw new AssertionError("Unexpected value for osConfig: "+osConfig);
        }
    }

    /**
     * Builds the workers#.properties file contents for the provided HttpdServer.
     */
    private static byte[] buildWorkersFile(HttpdServer hs, ByteArrayOutputStream bout) throws IOException, SQLException {
        AOServConnector conn = AOServDaemon.getConnector();
        List<HttpdWorker> workers=hs.getHttpdWorkers();
        int workerCount=workers.size();

        bout.reset();
        ChainWriter out = new ChainWriter(bout);
        try {
            out.print("worker.list=");
            for(int d=0;d<workerCount;d++) {
                if(d>0) out.print(',');
                out.print(workers.get(d).getCode().getCode());
            }
            out.print('\n');
            for(int d=0;d<workerCount;d++) {
                HttpdWorker worker=workers.get(d);
                String code=worker.getCode().getCode();
                out.print("\n"
                        + "worker.").print(code).print(".type=").print(worker.getHttpdJKProtocol(conn).getProtocol(conn).getProtocol()).print("\n"
                        + "worker.").print(code).print(".port=").print(worker.getNetBind().getPort().getPort()).print('\n');
            }
        } finally {
            out.close();
        }
        return bout.toByteArray();
    }

    /**
     * Builds the contents of a HttpdSiteBind file.
     */
    private static byte[] buildHttpdSiteBindFile(HttpdSiteBind bind, String siteInclude, ByteArrayOutputStream bout) throws IOException, SQLException {
        OperatingSystemConfiguration osConfig = OperatingSystemConfiguration.getOperatingSystemConfiguration();
        HttpdBind httpdBind = bind.getHttpdBind();
        NetBind netBind = httpdBind.getNetBind();
        int port = netBind.getPort().getPort();
        String ipAddress = netBind.getIPAddress().getIPAddress();
        HttpdSiteURL primaryHSU = bind.getPrimaryHttpdSiteURL();
        String primaryHostname = primaryHSU.getHostname();

        bout.reset();
        ChainWriter out = new ChainWriter(bout);
        try {
            out.print("<VirtualHost ").print(ipAddress).print(':').print(port).print(">\n"
                    + "    ServerName ").print(primaryHostname).print('\n'
            );
            List<HttpdSiteURL> altURLs=bind.getAltHttpdSiteURLs();
            if(!altURLs.isEmpty()) {
                out.print("    ServerAlias");
                for(HttpdSiteURL altURL : altURLs) {
                    out.print(' ').print(altURL.getHostname());
                }
                out.print('\n');
            }
            out.print("\n"
                    + "    CustomLog ").print(bind.getAccessLog()).print(" combined\n"
                    + "    ErrorLog ").print(bind.getErrorLog()).print("\n"
                    + "\n");
            String sslCert=bind.getSSLCertFile();
            if(sslCert!=null) {
                // If a .ca file exists with the same name as the certificate, use it instead of the OS default
                String sslCa = osConfig.getOpensslDefaultCaFile();
                if(sslCert.endsWith(".cert")) {
                    String possibleCa = sslCert.substring(0, sslCert.length()-5) + ".ca";
                    if(new File(possibleCa).exists()) sslCa = possibleCa;
                }

                out.print("    <IfModule mod_ssl.c>\n"
                        + "        SSLCertificateFile ").print(sslCert).print("\n"
                        + "        SSLCertificateKeyFile ").print(bind.getSSLCertKeyFile()).print("\n"
                        + "        SSLCACertificateFile ").print(sslCa).print("\n"
                        + "        <Files ~ \"\\.(cgi|shtml|phtml|php3?)$\">\n"
                        + "            SSLOptions +StdEnvVars\n"
                        + "        </Files>\n"
                        + "        SSLEngine On\n"
                        + "    </IfModule>\n"
                        + "\n"
                );
            }
            if(bind.getRedirectToPrimaryHostname()) {
                out.print("    # Redirect requests that are not to either the IP address or the primary hostname to the primary hostname\n"
                        + "    RewriteEngine on\n"
                        + "    RewriteCond %{HTTP_HOST} !=").print(primaryHostname).print(" [NC]\n"
                        + "    RewriteCond %{HTTP_HOST} !=").print(ipAddress).print("\n"
                        + "    RewriteRule ^(.*)$ ").print(primaryHSU.getURLNoSlash()).print("$1 [L,R=permanent]\n"
                        + "    \n");
            }
            out.print("    Include conf/hosts/").print(siteInclude).print("\n"
                    + "\n"
                    + "</VirtualHost>\n");
        } finally {
            out.close();
        }
        return bout.toByteArray();
    }

    /**
     * Reloads the configs for all provided <code>HttpdServer</code>s.
     */
    public static void reloadConfigs(Set<HttpdServer> serversNeedingReloaded) throws IOException {
        for(HttpdServer hs : serversNeedingReloaded) reloadConfigs(hs);
    }

    private static final Object processControlLock = new Object();
    private static void reloadConfigs(HttpdServer hs) throws IOException {
        synchronized(processControlLock) {
            String[] cmd={
                "/etc/rc.d/init.d/httpd"+hs.getNumber(),
                "reload" // Should this be restart for SSL changes?
            };
            AOServDaemon.exec(cmd);
        }
    }

    /**
     * Calls all Apache instances with the provided command.
     */
    private static void controlApache(String command) throws IOException, SQLException {
        synchronized(processControlLock) {
            for(HttpdServer hs : AOServDaemon.getThisAOServer().getHttpdServers()) {
                String[] cmd={
                    "/etc/rc.d/init.d/httpd"+hs.getNumber(),
                    command
                };
                AOServDaemon.exec(cmd);
            }
        }
    }

    /**
     * Restarts all Apache instances.
     */
    public static void restartApache() throws IOException, SQLException {
        controlApache("restart");
    }

    /**
     * Starts all Apache instances.
     */
    public static void startApache() throws IOException, SQLException {
        controlApache("start");
    }

    /**
     * Stops all Apache instances.
     */
    public static void stopApache() throws IOException, SQLException {
        controlApache("stop");
    }

    /**
     * Gets the shared library name for the given version of PHP.
     */
    private static final String getPhpLib(TechnologyVersion phpVersion) {
        String version=phpVersion.getVersion();
        if(version.equals("4") || version.startsWith("4.")) return "libphp4.so";
        if(version.equals("5") || version.startsWith("5.")) return "libphp5.so";
        throw new RuntimeException("Unsupported PHP version: "+version);
    }

    /**
     * Gets the major (first number only) form of a PHP version.
     */
    private static String getMajorPhpVersion(String version) {
        int pos = version.indexOf('.');
        return pos == -1 ? version : version.substring(0, pos);
    }

    private static final UnixFile[] centOsAlwaysDelete = {
        new UnixFile("/etc/httpd/conf/httpd1.conf.old"),
        new UnixFile("/etc/httpd/conf/httpd2.conf.old"),
        new UnixFile("/etc/httpd/conf/httpd3.conf.old"),
        new UnixFile("/etc/httpd/conf/httpd4.conf.old"),
        new UnixFile("/etc/httpd/conf/httpd5.conf.old"),
        new UnixFile("/etc/httpd/conf/httpd6.conf.old"),
        new UnixFile("/etc/httpd/conf/httpd7.conf.old"),
        new UnixFile("/etc/httpd/conf/httpd8.conf.old"),
        new UnixFile("/etc/httpd/conf/workers1.properties.old"),
        new UnixFile("/etc/httpd/conf/workers2.properties.old"),
        new UnixFile("/etc/httpd/conf/workers3.properties.old"),
        new UnixFile("/etc/httpd/conf/workers4.properties.old"),
        new UnixFile("/etc/httpd/conf/workers5.properties.old"),
        new UnixFile("/etc/httpd/conf/workers6.properties.old"),
        new UnixFile("/etc/httpd/conf/workers7.properties.old"),
        new UnixFile("/etc/httpd/conf/workers8.properties.old"),
        new UnixFile("/etc/rc.d/init.d/httpd"),
        new UnixFile("/opt/aoserv-daemon/init.d/httpd1"),
        new UnixFile("/opt/aoserv-daemon/init.d/httpd2"),
        new UnixFile("/opt/aoserv-daemon/init.d/httpd3"),
        new UnixFile("/opt/aoserv-daemon/init.d/httpd4"),
        new UnixFile("/opt/aoserv-daemon/init.d/httpd5"),
        new UnixFile("/opt/aoserv-daemon/init.d/httpd6"),
        new UnixFile("/opt/aoserv-daemon/init.d/httpd7"),
        new UnixFile("/opt/aoserv-daemon/init.d/httpd8")
    };

    /**
     * Fixes any filesystem stuff related to Apache.
     */
    private static void fixFilesystem(List<File> deleteFileList) throws IOException, SQLException {
        HttpdOperatingSystemConfiguration osConfig = HttpdOperatingSystemConfiguration.getHttpOperatingSystemConfiguration();
        if(osConfig==HttpdOperatingSystemConfiguration.CENTOS_5_I686_AND_X86_64) {
            Stat tempStat = new Stat();
            // Make sure these files don't exist.  They may be due to upgrades or a
            // result of RPM installs.
            for(UnixFile uf : centOsAlwaysDelete) {
                if(uf.getStat(tempStat).exists()) deleteFileList.add(uf.getFile());
            }
        }
    }

    /**
     * Rebuilds /etc/rc.d/init.d/httpd* init scripts.
     */
    private static void doRebuildInitScripts(AOServer aoServer, ByteArrayOutputStream bout, List<File> deleteFileList, Set<HttpdServer> serversNeedingReloaded) throws IOException, SQLException {
        List<HttpdServer> hss = aoServer.getHttpdServers();
        Set<String> dontDeleteFilenames = new HashSet<String>(hss.size()*4/3+1);
        for(HttpdServer hs : hss) {
            int num = hs.getNumber();
            bout.reset();
            ChainWriter out = new ChainWriter(bout);
            try {
                out.print("#!/bin/bash\n"
                        + "#\n"
                        + "# httpd").print(num).print("        Startup script for the Apache HTTP Server ").print(num).print("\n"
                        + "#\n"
                        + "# chkconfig: 345 85 15\n"
                        + "# description: Apache is a World Wide Web server.  It is used to serve \\\n"
                        + "#              HTML files and CGI.\n"
                        + "# processname: httpd").print(num).print("\n"
                        + "# config: /etc/httpd/conf/httpd").print(num).print(".conf\n"
                        + "# pidfile: /var/run/httpd").print(num).print(".pid\n"
                        + "\n");
                // mod_php requires MySQL and PostgreSQL in the path
                TechnologyVersion modPhpVersion = hs.getModPhpVersion();
                if(modPhpVersion!=null) {
                    String version = modPhpVersion.getVersion();
                    if(version.startsWith("4.4.")) {
                        out.print("export LD_LIBRARY_PATH=\"/opt/mysql-5.0-i686/lib:${LD_LIBRARY_PATH}\"\n"
                                + "export LD_LIBRARY_PATH=\"/opt/postgresql-7.3-i686/lib:${LD_LIBRARY_PATH}\"\n"
                                + "\n");
                    } else if(version.startsWith("5.2.")) {
                        out.print("export LD_LIBRARY_PATH=\"/opt/mysql-5.0-i686/lib:${LD_LIBRARY_PATH}\"\n"
                                + "export LD_LIBRARY_PATH=\"/opt/postgresql-8.1-i686/lib:${LD_LIBRARY_PATH}\"\n"
                                + "\n");
                    } else if(version.startsWith("5.3.")) {
                        out.print("export LD_LIBRARY_PATH=\"/opt/mysql-5.1-i686/lib:${LD_LIBRARY_PATH}\"\n"
                                + "export LD_LIBRARY_PATH=\"/opt/postgresql-8.3-i686/lib:${LD_LIBRARY_PATH}\"\n"
                                + "\n");
                    } else throw new SQLException("Unexpected version for mod_php: "+version);
                }
                out.print("NUM=").print(num).print("\n"
                        + ". /opt/aoserv-daemon/init.d/httpd\n");
            } finally {
                out.close();
            }
            String filename = "httpd"+num;
            dontDeleteFilenames.add(filename);
            if(
                FileUtils.writeIfNeeded(
                    bout.toByteArray(),
                    null,
                    new UnixFile(INIT_DIRECTORY+"/"+filename),
                    UnixFile.ROOT_UID,
                    UnixFile.ROOT_GID,
                    0700
                )
            ) {
                // Make start at boot
                AOServDaemon.exec(
                    new String[] {
                        "/sbin/chkconfig",
                        "--add",
                        filename
                    }
                );
                AOServDaemon.exec(
                    new String[] {
                        "/sbin/chkconfig",
                        filename,
                        "on"
                    }
                );
                // Make reload
                serversNeedingReloaded.add(hs);
            }
        }
        for(String filename : new File(INIT_DIRECTORY).list()) {
            if(filename.startsWith("httpd")) {
                String suffix = filename.substring(5);
                try {
                    // Parse to make sure is a httpd# filename
                    int num = Integer.parseInt(suffix);
                    if(!dontDeleteFilenames.contains(filename)) {
                        // chkconfig off
                        AOServDaemon.exec(
                            new String[] {
                                "/sbin/chkconfig",
                                filename,
                                "off"
                            }
                        );
                        // stop
                        String fullPath = INIT_DIRECTORY+"/"+filename;
                        AOServDaemon.exec(
                            new String[] {
                                fullPath,
                                "stop"
                            }
                        );
                        deleteFileList.add(new File(fullPath));
                    }
                } catch(NumberFormatException err) {
                    LogFactory.getLogger(HttpdServerManager.class).log(Level.WARNING, null, err);
                }
            }
        }
    }
}
