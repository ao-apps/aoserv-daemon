package com.aoindustries.aoserv.daemon.util;

/*
 * Copyright 2008-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.io.unix.Stat;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.util.BufferManager;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Reusable file utilities.
 *
 * @author  AO Industries, Inc.
 */
public class FileUtils {

    /**
     * Copies a resource to the provided output stream.
     */
    public static final void copyResource(Class clazz, String resource, OutputStream out) throws IOException {
        InputStream in=clazz.getResourceAsStream(resource);
        try {
            if(in==null) throw new IOException("Unable to find resource: "+resource);
            byte[] buff=BufferManager.getBytes();
            try {
                int ret;
                while((ret=in.read(buff, 0, BufferManager.BUFFER_SIZE))!=-1) out.write(buff, 0, ret);
            } finally {
                BufferManager.release(buff);
            }
        } finally {
            in.close();
        }
    }

    /**
     * Copies a resource to the provided filename, will not overwrite any existing file.
     * 
     * TODO: Copy to a temp file and rename into place.
     */
    public static final void copyResource(Class clazz, String resource, String filename, int uid, int gid, int mode) throws IOException {
        OutputStream out=new UnixFile(filename).getSecureOutputStream(uid, gid, mode, false);
        try {
            copyResource(clazz, resource, out);
        } finally {
            out.close();
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
        for (int i=0;i<destinations.length;i++) {
            ln(targetBase+destinations[i], srcBase+destinations[i], uid, gid);
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
            uf.getStat(ufStat);
        } else if(!ufStat.isDirectory()) throw new IOException("File exists and is not a directory: "+uf.getPath());
        if(ufStat.getMode()!=mode) uf.setMode(mode);
        if(ufStat.getUid()!=uid || ufStat.getGid()!=gid) uf.chown(uid, gid);
    }

    /**
     * If the file starts with the provided prefix, strips that prefix from the
     * file.  A new temp file is created and then renamed over the old.
     */
    public static void stripFilePrefix(UnixFile uf, String prefix, Stat tempStat) throws IOException {
        // Remove the auto warning if the site has recently become manual
        int prefixLen=prefix.length();
        uf.getStat(tempStat);
        if(tempStat.getSize()>=prefixLen) {
            UnixFile newUF=null;
            InputStream in=new BufferedInputStream(uf.getSecureInputStream());
            try {
                StringBuilder SB=new StringBuilder(prefixLen);
                int ch;
                while(SB.length()<prefixLen && (ch=in.read())!=-1) {
                    SB.append((char)ch);
                }
                if(SB.toString().equals(prefix)) {
                    newUF=UnixFile.mktemp(uf.getPath()+'.', false);
                    OutputStream out=new BufferedOutputStream(
                        newUF.getSecureOutputStream(
                            tempStat.getUid(),
                            tempStat.getGid(),
                            tempStat.getMode(),
                            true
                        )
                    );
                    try {
                        byte[] buff=BufferManager.getBytes();
                        try {
                            int ret;
                            while((ret=in.read(buff, 0, BufferManager.BUFFER_SIZE))!=-1) out.write(buff, 0, ret);
                        } finally {
                            BufferManager.release(buff);
                        }
                    } finally {
                        out.close();
                    }
                }
            } finally {
                in.close();
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
        int mode
    ) throws IOException {
        Stat tempStat = new Stat();
        if(file.getStat(tempStat).exists() && tempStat.isSymLink()) {
            file.delete();
            file.getStat(tempStat);
        }
        if(
            !tempStat.exists()
            || !file.contentEquals(newContents)
        ) {
            // Create temp file if none provided
            if(tempFile==null) tempFile = UnixFile.mktemp(file.getPath()+".", false);
            try {
                FileOutputStream newOut = tempFile.getSecureOutputStream(
                    uid,
                    gid,
                    mode,
                    true
                );
                try {
                    newOut.write(newContents);
                } finally {
                    newOut.close();
                }
                tempFile.renameTo(file);
            } finally {
                // If newFile still exists there was a problem and it should be
                // cleaned-up
                if(tempFile.getStat(tempStat).exists()) tempFile.delete();
            }
            return true;
        } else {
            return false;
        }
    }
    
    /**
     * Reads the contents of a File and returns as a String.
     */
    public static String readFileAsString(UnixFile file) throws IOException {
        StringBuilder SB = new StringBuilder();
        BufferedReader in = new BufferedReader(new FileReader(file.getFile()));
        try {
            int ch;
            while((ch=in.read())!=-1) SB.append((char)ch);
        } finally {
            in.close();
        }
        return SB.toString();
    }    
}
