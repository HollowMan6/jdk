/*
 * Copyright (c) 2008, 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package sun.nio.fs;

import java.nio.file.FileStore;
import java.nio.file.WatchService;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import static sun.nio.fs.LinuxNativeDispatcher.*;
import static sun.nio.fs.UnixConstants.*;

/**
 * Linux implementation of FileSystem
 */

class LinuxFileSystem extends UnixFileSystem {
    LinuxFileSystem(UnixFileSystemProvider provider, String dir) {
        super(provider, dir);
    }

    @Override
    public WatchService newWatchService()
        throws IOException
    {
        // assume 2.6.13 or newer
        return new LinuxWatchService(this);
    }


    // lazy initialization of the list of supported attribute views
    private static class SupportedFileFileAttributeViewsHolder {
        static final Set<String> supportedFileAttributeViews =
            supportedFileAttributeViews();
        private static Set<String> supportedFileAttributeViews() {
            Set<String> result = new HashSet<>();
            result.addAll(standardFileAttributeViews());
            // additional Linux-specific views
            result.add("dos");
            result.add("user");
            return Collections.unmodifiableSet(result);
        }
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        return SupportedFileFileAttributeViewsHolder.supportedFileAttributeViews;
    }

    @Override
    void copyNonPosixAttributes(int ofd, int nfd) {
        UnixUserDefinedFileAttributeView.copyExtendedAttributes(ofd, nfd);
    }

    /**
     * Returns object to iterate over the mount entries in the given fstab file.
     */
    List<UnixMountEntry> getMountEntries(String fstab) {
        ArrayList<UnixMountEntry> entries = new ArrayList<>();
        try {
            long fp = setmntent(Util.toBytes(fstab), Util.toBytes("r"));
            int maxLineSize = 1024;
            try {
                for (;;) {
                    int lineSize = getlinelen(fp);
                    if (lineSize == -1)
                        break;
                    if (lineSize > maxLineSize)
                        maxLineSize = lineSize;
                }
            } catch (UnixException x) {
                // nothing we need to do
            } finally {
                rewind(fp);
            }

            try {
                for (;;) {
                    UnixMountEntry entry = new UnixMountEntry();
                    // count in NUL character at the end
                    int res = getmntent(fp, entry, maxLineSize + 1);
                    if (res < 0)
                        break;
                    entries.add(entry);
                }
            } finally {
                endmntent(fp);
            }

        } catch (UnixException x) {
            // nothing we can do
        }
        return entries;
    }

    /**
     * Returns object to iterate over the mount entries in /etc/mtab
     */
    @Override
    List<UnixMountEntry> getMountEntries() {
        return getMountEntries("/etc/mtab");
    }

    @Override
    FileStore getFileStore(UnixMountEntry entry) throws IOException {
        return new LinuxFileStore(this, entry);
    }

    // --- file copying ---

    @Override
    void bufferedCopy(int dst, int src, long address,
                      int size, long addressToPollForCancel)
        throws UnixException
    {
        int advice = POSIX_FADV_SEQUENTIAL | // sequential data access
                     POSIX_FADV_NOREUSE    | // will access only once
                     POSIX_FADV_WILLNEED;    // will access in near future
        posix_fadvise(src, 0, 0, advice);

        super.bufferedCopy(dst, src, address, size, addressToPollForCancel);
    }

    @Override
    int directCopy(int dst, int src, long addressToPollForCancel)
        throws UnixException
    {
        int advice = POSIX_FADV_SEQUENTIAL | // sequential data access
                     POSIX_FADV_NOREUSE    | // will access only once
                     POSIX_FADV_WILLNEED;    // will access in near future
        posix_fadvise(src, 0, 0, advice);

        return directCopy0(dst, src, addressToPollForCancel);
    }

    // -- native methods --

    /**
     * Copies data between file descriptors {@code src} and {@code dst} using
     * a platform-specific function or system call possibly having kernel
     * support.
     *
     * @param dst destination file descriptor
     * @param src source file descriptor
     * @param addressToPollForCancel address to check for cancellation
     *        (a non-zero value written to this address indicates cancel)
     *
     * @return 0 on success, UNAVAILABLE if the platform function would block,
     *         UNSUPPORTED_CASE if the call does not work with the given
     *         parameters, or UNSUPPORTED if direct copying is not supported
     *         on this platform
     */
    private static native int directCopy0(int dst, int src,
                                          long addressToPollForCancel)
        throws UnixException;
}
