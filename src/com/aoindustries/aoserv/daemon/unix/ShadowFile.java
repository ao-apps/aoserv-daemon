package com.aoindustries.aoserv.daemon.unix;

/*
 * Copyright 2001-2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.aoserv.daemon.httpd.*;
import com.aoindustries.io.*;
import com.aoindustries.io.unix.*;
import com.aoindustries.profiler.*;
import com.aoindustries.util.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * A <code>ShadowFileEntry</code> represents one line of the
 * <code>/etc/shadow</code> file on a Unix server.
 *
 * @author  AO Industries, Inc.
 */
final public class ShadowFile {

    public static final UnixFile
        newShadowFile=new UnixFile("/etc/shadow.new"),
        shadowFile=new UnixFile("/etc/shadow"),
        backupShadowFile=new UnixFile("/etc/shadow.old")
    ;

    /** Locks the shadow file for updates */
    private static final Object	shadowLock=new Object();

    private ShadowFile(String line) {
        Profiler.startProfile(Profiler.INSTANTANEOUS, ShadowFile.class, "<init>(String)", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    /**
     * Gets the encypted password for one user on the system.
     * <p>
     * This method is synchronized with <code>doRebuild</code> to ensure that
     * passwords are never lost during updates.
     */
    public static String getEncryptedPassword(String username) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, ShadowFile.class, "getEncryptedPassword(String)", null);
        try {
            int osv=AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPKey();
            if(
                osv!=OperatingSystemVersion.MANDRAKE_10_1_I586
                && osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
                && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            synchronized(shadowLock) {
                BufferedReader in = new BufferedReader(new InputStreamReader(new BufferedInputStream(new FileInputStream(shadowFile.getFile()))));
		String password;
                try {
                    password=LinuxAccount.NO_PASSWORD_CONFIG_VALUE;
                    String line;
                    while ((line = in.readLine()) != null) {
                        ShadowFileEntry entry = new ShadowFileEntry(line);
                        if(entry.username.equals(username)) {
                            password=entry.password;
                            break;
                        }
                    }
                } finally {
                    in.close();
                }

                return password;
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void rebuildShadowFile(List<LinuxServerAccount> accounts) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, ShadowFile.class, "rebuildShadowFile(List<LinuxServerAccount>)", null);
        try {
            int osv=AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPKey();
            if(
                osv!=OperatingSystemVersion.MANDRAKE_10_1_I586
                && osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
                && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            synchronized(shadowLock) {
                /*
                 * Get the old data from /etc/shadow
                 */
                Map<String,ShadowFileEntry> shadowEntries = new HashMap<String,ShadowFileEntry>();
                BufferedReader in = new BufferedReader(new InputStreamReader(new BufferedInputStream(new FileInputStream(shadowFile.getFile()))));
                try {
                    String line;
                    while ((line = in.readLine()) != null) {
                        ShadowFileEntry entry = new ShadowFileEntry(line);
                        if (shadowEntries.containsKey(entry.username)) throw new IllegalArgumentException("Shadow file contains duplicate entry: " + line);
                        shadowEntries.put(entry.username, entry);
                    }
                } finally {
                    in.close();
                }

                /*
                 * Write the new /etc/shadow file, creating default entries if needed.
                 */
                ChainWriter out = new ChainWriter(
                    new BufferedOutputStream(
                        newShadowFile.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0600, true)
                    )
                );
                try {
                    boolean rootFound=false;
                    for (int c = 0; c < accounts.size(); c++) {
                        String username = accounts.get(c)
                            .getLinuxAccount()
                            .getUsername()
                            .getUsername()
                        ;
                        if(username.equals(LinuxAccount.ROOT)) {
                            ShadowFileEntry entry = shadowEntries.get(username);
                            if (entry == null) entry = new ShadowFileEntry(username);
                            out.print(entry.toString());
                            out.print('\n');
                            rootFound=true;
                            break;
                        }
                    }
                    if(!rootFound) throw new SQLException("root user not found while creating "+newShadowFile.getFilename());
                    for (int c = 0; c < accounts.size(); c++) {
                        String username = accounts.get(c)
                            .getLinuxAccount()
                            .getUsername()
                            .getUsername()
                        ;
                        if(!username.equals(LinuxAccount.ROOT)) {
                            ShadowFileEntry entry = shadowEntries.get(username);
                            if (entry == null) entry = new ShadowFileEntry(username);
                            out.print(entry.toString());
                            out.print('\n');
                        }
                    }
                } finally {
                    out.flush();
                    out.close();
                }

                if(newShadowFile.getStat().getSize()>0) {
                    shadowFile.renameTo(backupShadowFile);
                    newShadowFile.renameTo(shadowFile);
                } else throw new IOException(newShadowFile.getFilename()+" has zero or unknown length");
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Sets the encypted password for one user on the system.  This password must already
     * be hashed using the crypt or MD5 algorithm.
     * <p>
     * This method is synchronized with <code>doRebuild</code> to ensure that
     * passwords are never lost during updates.
     */
    public static void setEncryptedPassword(String username, String encryptedPassword) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, ShadowFile.class, "setEncryptedPassword(String,String)", null);
        try {
            int osv=AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPKey();
            if(
                osv!=OperatingSystemVersion.MANDRAKE_10_1_I586
                && osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
                && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            synchronized(shadowLock) {
                /*
                 * Get the old data from /etc/shadow
                 */
                List<ShadowFileEntry> shadowEntries = new ArrayList<ShadowFileEntry>();
		boolean userFound=false;
                BufferedReader in = new BufferedReader(new InputStreamReader(new BufferedInputStream(new FileInputStream(shadowFile.getFile()))));
                try {
                    // Reset if already exists
                    String line;
                    while ((line = in.readLine()) != null) {
                        ShadowFileEntry entry = new ShadowFileEntry(line);
                        if(entry.username.equals(username)) {
                            userFound=true;
                            entry.password=encryptedPassword;
                        }
                        shadowEntries.add(entry);
                    }
                } finally {
                    in.close();
                }

                // Add if does not yet exist
                if(!userFound) shadowEntries.add(new ShadowFileEntry(username+':'+encryptedPassword));

                /*
                 * Write the new /etc/shadow file.
                 */
                ChainWriter out = new ChainWriter(
                    new BufferedOutputStream(
                        newShadowFile.getSecureOutputStream(UnixFile.ROOT_UID, UnixFile.ROOT_GID, 0600, true)
                    )
                );
                try {
                    for (ShadowFileEntry entry : shadowEntries) {
                        out.print(entry.toString());
                        out.print('\n');
                    }
                } finally {
                    out.flush();
                    out.close();
                }

                /*
                 * Move the new file into place.
                 */
                shadowFile.renameTo(backupShadowFile);
                newShadowFile.renameTo(shadowFile);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Sets the password for one user on the system.
     */
    public static void setPassword(String username, String plaintext) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, ShadowFile.class, "setPassword(String,String)", null);
        try {
            setEncryptedPassword(
                username,
                plaintext==null || plaintext.length()==0
                ?LinuxAccount.NO_PASSWORD_CONFIG_VALUE
                :UnixFile.crypt(plaintext, AOServDaemon.getRandom())
            );
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
}
