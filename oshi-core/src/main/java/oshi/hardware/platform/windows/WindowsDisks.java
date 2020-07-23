/**
 * MIT License
 *
 * Copyright (c) 2010 - 2020 The OSHI Project Contributors: https://github.com/oshi/oshi/graphs/contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package oshi.hardware.platform.windows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jna.platform.win32.COM.WbemcliUtil.WmiResult; // NOSONAR squid:S1191

import oshi.annotation.concurrent.ThreadSafe;
import oshi.driver.windows.perfmon.PhysicalDisk;
import oshi.driver.windows.perfmon.PhysicalDisk.PhysicalDiskProperty;
import oshi.driver.windows.wmi.Win32DiskDrive;
import oshi.driver.windows.wmi.Win32DiskDrive.DiskDriveProperty;
import oshi.driver.windows.wmi.Win32DiskDriveToDiskPartition;
import oshi.driver.windows.wmi.Win32DiskDriveToDiskPartition.DriveToPartitionProperty;
import oshi.driver.windows.wmi.Win32DiskPartition;
import oshi.driver.windows.wmi.Win32DiskPartition.DiskPartitionProperty;
import oshi.driver.windows.wmi.Win32LogicalDiskToPartition;
import oshi.driver.windows.wmi.Win32LogicalDiskToPartition.DiskToPartitionProperty;
import oshi.hardware.HWDiskStore;
import oshi.hardware.HWPartition;
import oshi.util.ParseUtil;
import oshi.util.platform.windows.WmiUtil;
import oshi.util.tuples.Pair;

/**
 * Windows hard disk implementation.
 */
@ThreadSafe
public final class WindowsDisks {

    private static final Logger LOG = LoggerFactory.getLogger(WindowsDisks.class);

    private static final String PHYSICALDRIVE_PREFIX = "\\\\.\\PHYSICALDRIVE";

    private static final Pattern DEVICE_ID = Pattern.compile(".*\\.DeviceID=\"(.*)\"");

    private WindowsDisks() {
    }

    /**
     * Updates the statistics on a disk store.
     *
     * @param diskStore
     *            the {@link oshi.hardware.HWDiskStore} to update.
     * @return {@code true} if the update was (probably) successful.
     */
    public static boolean updateDiskStats(HWDiskStore diskStore) {
        String index = null;
        HWPartition[] partitions = diskStore.getPartitions();
        if (partitions.length > 0) {
            // If a partition exists on this drive, the major property
            // corresponds to the disk index, so use it.
            index = Integer.toString(partitions[0].getMajor());
        } else if (diskStore.getName().startsWith(PHYSICALDRIVE_PREFIX)) {
            // If no partition exists, Windows reliably uses a name to match the
            // disk index. That said, the skeptical person might wonder why a
            // disk has read/write statistics without a partition, and wonder
            // why this branch is even relevant as an option. The author of this
            // comment does not have an answer for this valid question.
            index = diskStore.getName().substring(PHYSICALDRIVE_PREFIX.length(), diskStore.getName().length());
        } else {
            // The author of this comment cannot fathom a circumstance in which
            // the code reaches this point, but just in case it does, here's the
            // correct response. If you get this log warning, the circumstances
            // would be of great interest to the project's maintainers.
            LOG.warn("Couldn't match index for {}", diskStore.getName());
            return false;
        }
        DiskStats stats = queryReadWriteStats(index);
        if (stats.readMap.containsKey(index)) {
            diskStore.setReads(stats.readMap.getOrDefault(index, 0L));
            diskStore.setReadBytes(stats.readByteMap.getOrDefault(index, 0L));
            diskStore.setWrites(stats.writeMap.getOrDefault(index, 0L));
            diskStore.setWriteBytes(stats.writeByteMap.getOrDefault(index, 0L));
            diskStore.setCurrentQueueLength(stats.queueLengthMap.getOrDefault(index, 0L));
            diskStore.setTransferTime(stats.timeStamp - stats.idleTimeMap.getOrDefault(index, stats.timeStamp));
            diskStore.setTimeStamp(stats.timeStamp);
            return true;
        } else {
            return false;
        }
    }

    /**
     * Gets the disks on this machine
     *
     * @return an array of {@link HWDiskStore} objects representing the disks
     */
    public static HWDiskStore[] getDisks() {
        List<HWDiskStore> result;
        result = new ArrayList<>();
        DiskStats stats = queryReadWriteStats(null);
        PartitionMaps maps = queryPartitionMaps();

        WmiResult<DiskDriveProperty> vals = Win32DiskDrive.queryDiskDrive();
        for (int i = 0; i < vals.getResultCount(); i++) {
            HWDiskStore ds = new HWDiskStore();
            ds.setName(WmiUtil.getString(vals, DiskDriveProperty.NAME, i));
            ds.setModel(String.format("%s %s", WmiUtil.getString(vals, DiskDriveProperty.MODEL, i),
                    WmiUtil.getString(vals, DiskDriveProperty.MANUFACTURER, i)).trim());
            // Most vendors store serial # as a hex string; convert
            ds.setSerial(ParseUtil.hexStringToString(WmiUtil.getString(vals, DiskDriveProperty.SERIALNUMBER, i)));
            String index = Integer.toString(WmiUtil.getUint32(vals, DiskDriveProperty.INDEX, i));
            ds.setReads(stats.readMap.getOrDefault(index, 0L));
            ds.setReadBytes(stats.readByteMap.getOrDefault(index, 0L));
            ds.setWrites(stats.writeMap.getOrDefault(index, 0L));
            ds.setWriteBytes(stats.writeByteMap.getOrDefault(index, 0L));
            ds.setCurrentQueueLength(stats.queueLengthMap.getOrDefault(index, 0L));
            ds.setTransferTime(stats.timeStamp - stats.idleTimeMap.getOrDefault(index, stats.timeStamp));
            ds.setTimeStamp(stats.timeStamp);
            ds.setSize(WmiUtil.getUint64(vals, DiskDriveProperty.SIZE, i));
            // Get partitions
            List<HWPartition> partitions = new ArrayList<>();
            List<String> partList = maps.driveToPartitionMap.get(ds.getName());
            if (partList != null && !partList.isEmpty()) {
                for (String part : partList) {
                    if (maps.partitionMap.containsKey(part)) {
                        partitions.addAll(maps.partitionMap.get(part));
                    }
                }
            }
            ds.setPartitions(partitions.toArray(new HWPartition[0]));
            // Add to list
            result.add(ds);
        }
        return result.toArray(new HWDiskStore[0]);
    }

    /**
     * Populates the maps for the specified index. If the index is null, populates
     * all the maps
     *
     * @param index
     *            The index to populate/update maps for
     */
    private static DiskStats queryReadWriteStats(String index) {
        // Create object to hold and return results
        DiskStats stats = new DiskStats();
        Pair<List<String>, Map<PhysicalDiskProperty, List<Long>>> instanceValuePair = PhysicalDisk.queryDiskCounters();
        List<String> instances = instanceValuePair.getA();
        Map<PhysicalDiskProperty, List<Long>> valueMap = instanceValuePair.getB();
        stats.timeStamp = System.currentTimeMillis();
        List<Long> readList = valueMap.get(PhysicalDiskProperty.DISKREADSPERSEC);
        List<Long> readByteList = valueMap.get(PhysicalDiskProperty.DISKREADBYTESPERSEC);
        List<Long> writeList = valueMap.get(PhysicalDiskProperty.DISKWRITESPERSEC);
        List<Long> writeByteList = valueMap.get(PhysicalDiskProperty.DISKWRITEBYTESPERSEC);
        List<Long> queueLengthList = valueMap.get(PhysicalDiskProperty.CURRENTDISKQUEUELENGTH);
        List<Long> idleTimeList = valueMap.get(PhysicalDiskProperty.PERCENTIDLETIME);

        if (instances.isEmpty() || readList == null || readByteList == null || writeList == null
                || writeByteList == null || queueLengthList == null || idleTimeList == null) {
            return stats;
        }
        for (int i = 0; i < instances.size(); i++) {
            String name = getIndexFromName(instances.get(i));
            // If index arg passed, only update passed arg
            if (index != null && !index.equals(name)) {
                continue;
            }
            stats.readMap.put(name, readList.get(i));
            stats.readByteMap.put(name, readByteList.get(i));
            stats.writeMap.put(name, writeList.get(i));
            stats.writeByteMap.put(name, writeByteList.get(i));
            stats.queueLengthMap.put(name, queueLengthList.get(i));
            stats.idleTimeMap.put(name, idleTimeList.get(i) / 10_000L);
        }
        return stats;
    }

    private static PartitionMaps queryPartitionMaps() {
        // Create object to hold and return results
        PartitionMaps maps = new PartitionMaps();

        // For Regexp matching DeviceIDs
        Matcher mAnt;
        Matcher mDep;

        // Map drives to partitions
        WmiResult<DriveToPartitionProperty> drivePartitionMap = Win32DiskDriveToDiskPartition.queryDriveToPartition();
        for (int i = 0; i < drivePartitionMap.getResultCount(); i++) {
            mAnt = DEVICE_ID.matcher(WmiUtil.getRefString(drivePartitionMap, DriveToPartitionProperty.ANTECEDENT, i));
            mDep = DEVICE_ID.matcher(WmiUtil.getRefString(drivePartitionMap, DriveToPartitionProperty.DEPENDENT, i));
            if (mAnt.matches() && mDep.matches()) {
                maps.driveToPartitionMap
                        .computeIfAbsent(mAnt.group(1).replaceAll("\\\\\\\\", "\\\\"), x -> new ArrayList<>())
                        .add(mDep.group(1));
            }
        }

        // Map partitions to logical disks
        WmiResult<DiskToPartitionProperty> diskPartitionMap = Win32LogicalDiskToPartition.queryDiskToPartition();
        for (int i = 0; i < diskPartitionMap.getResultCount(); i++) {
            mAnt = DEVICE_ID.matcher(WmiUtil.getRefString(diskPartitionMap, DiskToPartitionProperty.ANTECEDENT, i));
            mDep = DEVICE_ID.matcher(WmiUtil.getRefString(diskPartitionMap, DiskToPartitionProperty.DEPENDENT, i));
            long size = WmiUtil.getUint64(diskPartitionMap, DiskToPartitionProperty.ENDINGADDRESS, i)
                    - WmiUtil.getUint64(diskPartitionMap, DiskToPartitionProperty.STARTINGADDRESS, i) + 1L;
            if (mAnt.matches() && mDep.matches()) {
                if (maps.partitionToLogicalDriveMap.containsKey(mAnt.group(1))) {
                    maps.partitionToLogicalDriveMap.get(mAnt.group(1)).add(new Pair<>(mDep.group(1) + "\\", size));
                } else {
                    List<Pair<String, Long>> list = new ArrayList<>();
                    list.add(new Pair<>(mDep.group(1) + "\\", size));
                    maps.partitionToLogicalDriveMap.put(mAnt.group(1), list);
                }
            }
        }

        // Next, get all partitions and create objects
        WmiResult<DiskPartitionProperty> hwPartitionQueryMap = Win32DiskPartition.queryPartition();
        for (int i = 0; i < hwPartitionQueryMap.getResultCount(); i++) {
            String deviceID = WmiUtil.getString(hwPartitionQueryMap, DiskPartitionProperty.DEVICEID, i);
            List<Pair<String, Long>> logicalDrives = maps.partitionToLogicalDriveMap.get(deviceID);
            if (logicalDrives == null) {
                continue;
            }
            for (int j = 0; j < logicalDrives.size(); j++) {
                Pair<String, Long> logicalDrive = logicalDrives.get(j);
                if (logicalDrive != null && !logicalDrive.getA().isEmpty()) {
                    HWPartition pt = new HWPartition(
                            WmiUtil.getString(hwPartitionQueryMap, DiskPartitionProperty.NAME, i),
                            WmiUtil.getString(hwPartitionQueryMap, DiskPartitionProperty.TYPE, i),
                            WmiUtil.getString(hwPartitionQueryMap, DiskPartitionProperty.DESCRIPTION, i), "",
                            logicalDrive.getB(),
                            WmiUtil.getUint32(hwPartitionQueryMap, DiskPartitionProperty.DISKINDEX, i),
                            WmiUtil.getUint32(hwPartitionQueryMap, DiskPartitionProperty.INDEX, i),
                            logicalDrive.getA());
                    if (maps.partitionMap.containsKey(deviceID)) {
                        maps.partitionMap.get(deviceID).add(pt);
                    } else {
                        List<HWPartition> ptlist = new ArrayList<>();
                        ptlist.add(pt);
                        maps.partitionMap.put(deviceID, ptlist);
                    }
                }
            }
        }
        return maps;
    }

    /**
     * Parse a drive name like "0 C:" to just the index "0"
     *
     * @param s
     *            A drive name to parse
     * @return The first space-delimited value
     */
    private static String getIndexFromName(String s) {
        if (s.isEmpty()) {
            return s;
        }
        return s.split("\\s")[0];
    }

    /**
     * Maps to store read/write bytes per drive index
     */
    private static final class DiskStats {
        private final Map<String, Long> readMap = new HashMap<>();
        private final Map<String, Long> readByteMap = new HashMap<>();
        private final Map<String, Long> writeMap = new HashMap<>();
        private final Map<String, Long> writeByteMap = new HashMap<>();
        private final Map<String, Long> queueLengthMap = new HashMap<>();
        private final Map<String, Long> idleTimeMap = new HashMap<>();
        private long timeStamp;
    }

    /**
     * Maps for the partition structure
     */
    private static final class PartitionMaps {
        private final Map<String, List<String>> driveToPartitionMap = new HashMap<>();
        private final Map<String, List<Pair<String, Long>>> partitionToLogicalDriveMap = new HashMap<>();
        private final Map<String, List<HWPartition>> partitionMap = new HashMap<>();
    }
}