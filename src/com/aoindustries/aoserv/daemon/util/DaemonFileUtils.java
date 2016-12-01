/*
 * Copyright 2008-2009, 2014, 2015, 2016 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.daemon.util;

import com.aoindustries.io.IoUtils;
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Reusable file utilities.
 *
 * @author  AO Industries, Inc.
 */
public class DaemonFileUtils {

	/**
	 * Make no instances.
	 */
	private DaemonFileUtils() {
	}

    /**
     * Copies a resource to the provided output stream.
     */
    public static void copyResource(Class<?> clazz, String resource, OutputStream out) throws IOException {
        try (InputStream in = clazz.getResourceAsStream(resource)) {
            if(in==null) throw new IOException("Unable to find resource: "+resource);
			IoUtils.copy(in, out);
        }
    }

    /**
     * Copies a resource to the provided filename, will not overwrite any existing file.
     * 
     * TODO: Copy to a temp file and rename into place.
     */
    public static void copyResource(Class<?> clazz, String resource, String filename, int uid, int gid, int mode, int uid_min, int gid_min) throws IOException {
        try (OutputStream out = new UnixFile(filename).getSecureOutputStream(uid, gid, mode, false, uid_min, gid_min)) {
            copyResource(clazz, resource, out);
        }
    }
    
    /**
     * Creates a symbolic link.  To aid in debugging, writes the filename and target to System.err if
     * an <code>IOException</code> occurs; exception is then rethrown.
     */
    public static void ln(String target, String filename, int uid, int gid) throws IOException {
        try {
            new UnixFile(filename).symLink(target).chown(uid, gid);
        } catch (IOException e) {
            System.err.println("ln: filename: "+filename+"   destination: "+target);
            throw e;
        }
    }
    
    /**
     * Creates symbolic links to all items in a directory.
     * 
     * @see  #ln(String,String,int,int)
     */
    public static void lnAll(String targetBase, String srcBase, int uid, int gid) throws IOException {
        String[] destinations=new UnixFile(targetBase).list();
		for (String destination : destinations) {
			ln(targetBase + destination, srcBase + destination, uid, gid);
		}
    }

    /**
     * Creates a directory, if needed.  If already exists makes sure it is a directory.
     * Also sets or resets the ownership and permissions.
     */
    public static void mkdir(String dirName, int mode, int uid, int gid) throws IOException {
        mkdir(new UnixFile(dirName), mode, uid, gid);
    }

    /**
     * Creates a directory, if needed.  If already exists makes sure it is a directory.
     * Also sets or resets the ownership and permissions.
     */
    public static void mkdir(UnixFile uf, int mode, int uid, int gid) throws IOException {
        Stat ufStat = uf.getStat();
        if(!ufStat.exists()) {
            uf.mkdir();
            ufStat = uf.getStat();
        } else if(!ufStat.isDirectory()) throw new IOException("File exists and is not a directory: "+uf.getPath());
        if(ufStat.getMode()!=mode) uf.setMode(mode);
        if(ufStat.getUid()!=uid || ufStat.getGid()!=gid) uf.chown(uid, gid);
    }

    /**
     * If the file starts with the provided prefix, strips that prefix from the
     * file.  A new temp file is created and then renamed over the old.
     */
    public static void stripFilePrefix(UnixFile uf, String prefix, int uid_min, int gid_min) throws IOException {
        // Remove the auto warning if the site has recently become manual
        int prefixLen=prefix.length();
        Stat ufStat = uf.getStat();
        if(ufStat.getSize()>=prefixLen) {
            UnixFile newUF=null;
            try (InputStream in = new BufferedInputStream(uf.getSecureInputStream(uid_min, gid_min))) {
                StringBuilder SB=new StringBuilder(prefixLen);
                int ch;
                while(SB.length()<prefixLen && (ch=in.read())!=-1) {
                    SB.append((char)ch);
                }
                if(SB.toString().equals(prefix)) {
                    newUF=UnixFile.mktemp(uf.getPath()+'.', false);
                    try (OutputStream out = new BufferedOutputStream(
						newUF.getSecureOutputStream(
							ufStat.getUid(),
							ufStat.getGid(),
							ufStat.getMode(),
							true,
							uid_min,
							gid_min
						)
					)) {
						IoUtils.copy(in, out);
                    }
                }
            }
            if(newUF!=null) newUF.renameTo(uf);
        }
    }

    /**
     * Overwrites a file only when missing or not equal to the provided
     * content.  Will first create a new file into <code>tempFile</code>,
     * set the ownership and permissions, and then rename over any
     * existing files.  If <code>tempFile</code> exists and <code>file</code>
     * has to be written, <code>tempFile</code> will be overwritten.
     * If file is a symlink, will be removed first.
     * 
     * @param  tempFile  if <code>null</code>, a randomly-generated filename will
     *                   be used
     *
     * @return  <code>true</code> if the file was created or overwritten,
     *          <code>false</code> when no changes were made
     */
    public static boolean writeIfNeeded(
        byte[] newContents,
        UnixFile tempFile,
        UnixFile file,
        int uid,
        int gid,
        int mode,
		int uid_min,
		int gid_min
    ) throws IOException {
        Stat fileStat = file.getStat();
        if(fileStat.exists() && fileStat.isSymLink()) {
            file.delete();
            fileStat = file.getStat();
        }
        if(
            !fileStat.exists()
            || !file.contentEquals(newContents)
        ) {
            // Create temp file if none provided
            if(tempFile==null) tempFile = UnixFile.mktemp(file.getPath()+".", false);
            try {
                try (
					FileOutputStream newOut = tempFile.getSecureOutputStream(
						uid,
						gid,
						mode,
						true,
						uid_min,
						gid_min
					)
				) {
                    newOut.write(newContents);
                }
                tempFile.renameTo(file);
            } finally {
                // If newFile still exists there was a problem and it should be
                // cleaned-up
                if(tempFile.getStat().exists()) tempFile.delete();
            }
            return true;
        } else {
            return false;
        }
    }
}
