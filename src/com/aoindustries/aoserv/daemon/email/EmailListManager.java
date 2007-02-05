package com.aoindustries.aoserv.daemon.email;

/*
 * Copyright 2000-2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.daemon.*;
import com.aoindustries.io.unix.*;
import com.aoindustries.profiler.*;
import java.io.*;
import java.sql.*;

/**
 * @author  AO Industries, Inc.
 */
final public class EmailListManager {

    private EmailListManager() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, EmailListManager.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }

    /**
     * Reads the address list from the file system.
     */
    public static String getEmailListFile(String path) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, EmailListManager.class, "getEmailListFile(String)", null);
        try {
            int osv=AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPKey();
            if(
                osv!=OperatingSystemVersion.MANDRAKE_10_1_I586
                && osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
                && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            UnixFile file = new UnixFile(path);
            Stat fileStat = file.getStat();
            StringBuilder sb=new StringBuilder((int) fileStat.getSize());
            InputStream fin=new BufferedInputStream(file.getSecureInputStream());
            int ch;
            while ((ch = fin.read()) != -1) sb.append((char) ch);
            return sb.toString();
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Constructs a <code>EmailList</code> providing all information.  The
     * new <code>EmailList</code> is stored in the database.
     */
    public static void removeEmailListAddresses(String path) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, EmailListManager.class, "removeEmailListAddresses(String)", null);
        try {
            int osv=AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPKey();
            if(
                osv!=OperatingSystemVersion.MANDRAKE_10_1_I586
                && osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
                && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            File file = new File(path);
            if(file.exists() && !file.delete()) throw new IOException("Unable to delete file: " + path);
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Writes the address list to the file system.
     */
    public synchronized static void setEmailListFile(
        String path,
        String file,
        int uid,
        int gid,
        int mode
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, EmailListManager.class, "setEmailListAddresses(String,String,int,int,int)", null);
        try {
            int osv=AOServDaemon.getThisAOServer().getServer().getOperatingSystemVersion().getPKey();
            if(
                osv!=OperatingSystemVersion.MANDRAKE_10_1_I586
                && osv!=OperatingSystemVersion.MANDRIVA_2006_0_I586
                && osv!=OperatingSystemVersion.REDHAT_ES_4_X86_64
            ) throw new SQLException("Unsupported OperatingSystemVersion: "+osv);

            // Remove any '/r'
            StringBuilder SB=new StringBuilder();
            int len=file.length();
            for(int c=0;c<len;c++) {
                char ch=file.charAt(c);
                if(ch!='\r') SB.append(ch);
            }
            // Make sure ends with '\n'
            if(SB.length()>0 && SB.charAt(SB.length()-1)!='\n') SB.append('\n');

            // If a majordomo list, add any new directories
            boolean isMajordomo=path.startsWith(MajordomoServer.MAJORDOMO_SERVER_DIRECTORY);
            if(isMajordomo) {
                UnixFile pathUF=new UnixFile(path);
                UnixFile listDir=pathUF.getParent();
                if(!listDir.getStat().exists()) {
                    UnixFile serverDir=listDir.getParent();
                    if(!serverDir.getStat().exists()) {
                        serverDir.mkdir().setMode(0750);
                    }
                    listDir.mkdir().setMode(0750);
                }
            }

            UnixFile tempUF=UnixFile.mktemp(path+".new.");
            PrintWriter out=new PrintWriter(
                new BufferedOutputStream(
                    tempUF.getSecureOutputStream(uid, gid, mode, true)
                )
            );
	    try {
		out.print(SB.toString());
	    } finally {
		out.flush();
		out.close();
	    }

            // Move the new file into place
            tempUF.renameTo(new UnixFile(path));
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
}
