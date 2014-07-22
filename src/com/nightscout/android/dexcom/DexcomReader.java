package com.nightscout.android.dexcom;

import android.content.Context;
import android.hardware.usb.UsbManager;
import android.os.AsyncTask;
import android.util.Log;
import com.nightscout.android.dexcom.USB.UsbSerialDriver;
import com.nightscout.android.dexcom.USB.UsbSerialProber;
import com.nightscout.android.dexcom.Constants;
import com.nightscout.android.dexcom.DexcomPacket;

import java.io.*;
import java.nio.ByteBuffer;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

//Still kludgy
//Newer, similar to Dex's architecture, classes are in progress, but this works reliably, if not the 
//most efficient, elegant design around.

public class DexcomReader extends AsyncTask<UsbSerialDriver, Object, Object>{

    private static final String TAG = DexcomReader.class.getSimpleName();
    private final String EPOCH = "01-01-2009";
    private UsbSerialDriver mSerialDevice;
    public String bGValue;
    public String displayTime;
    public String trend;
    public EGVRecord[] mRD;

    public DexcomReader (UsbSerialDriver device) {
        mSerialDevice = device;
    }

    public void readFromReceiver(Context context, int pageOffset) {

        assert pageOffset < 1 : "Page offset must be greater than 1";

        //locate the EGV data pages
        byte[] dexcomPageRange = getEGVDataPageRange();
        //Get the last 4 pages
        byte[] databasePages = getLastFourPages(dexcomPageRange, pageOffset);
        //Parse 'dem pages
        EGVRecord[] mostRecentData = parseDatabasePages(databasePages);

        // make first read public
        mRD = mostRecentData;
    }

    //Not being used, but this is a nice to have if we want to kill the receiver, etc from
    //UI
    public void shutDownReceiver(Context context){

        UsbManager manager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        UsbSerialDriver mSerialDevice = UsbSerialProber.acquire(manager);
        if (mSerialDevice != null) {
            try {
                mSerialDevice.open();
                DexcomPacket p = new DexcomPacket(Constants.SHUTDOWN_RECEIVER);
                try {
                    mSerialDevice.write(p.Compose(), 200);
                } catch (IOException e) {
                    Log.e(TAG, "unable to write to serial device", e);
                }
            } catch (IOException e) {
                Log.e(TAG, "unable to shutDownReceiver", e);
            }
        }
    }

    public Date getDisplayTime() {
        int dt = getSystemTime() + getDisplayTimeOffset();
        SimpleDateFormat f = new SimpleDateFormat("dd-MM-yyyy");
        Date epoch;

        try {
            epoch = f.parse(EPOCH);
        } catch (ParseException e) {
            Log.e(TAG, "Unable to parse date: " + EPOCH + ", using current time", e);
            epoch = new Date();
        }

        // Epoch is PST, but but having epoch have user timezone added, then don't have to add to the
        // display time
        long milliseconds = epoch.getTime();
        long timeAdd = milliseconds + (1000L * dt);
        TimeZone tz = TimeZone.getDefault();
        if (tz.inDaylightTime(new Date())) timeAdd = timeAdd - 3600000L;
        Date displayTime = new Date(timeAdd);

        Log.d(TAG, "The devices Display Time is: " + displayTime.toString());

        return displayTime;
    }

    private int getSystemTime() {
        DexcomPacket p = new DexcomPacket(Constants.READ_SYSTEM_TIME);

        try {
            mSerialDevice.write(p.Compose(), 200);

        } catch (IOException e) {
            Log.e(TAG, "Unable to write to serial device", e);
        }

        byte[] readData = new byte[256];
        try {
            mSerialDevice.read(readData, 200);
        } catch (IOException e) {
            Log.e(TAG, "Unable to read from serial device", e);
        }

        int systemTime =  readData[4] & 0xFF |
                (readData[5] & 0xFF) << 8 |
                (readData[6] & 0xFF) << 16 |
                (readData[7] & 0xFF) << 24;

        Log.d(TAG, "The devices System Time is " + systemTime);

        return systemTime;
    }

    private int getDisplayTimeOffset() {
        DexcomPacket p = new DexcomPacket(Constants.READ_DISPLAY_TIME_OFFSET);

        try {
            mSerialDevice.write(p.Compose(), 200);
        } catch (IOException e) {
            Log.e(TAG, "Unable to write to serial device", e);
        }

        byte[] readData = new byte[256];
        try {
            mSerialDevice.read(readData, 200);
        } catch (IOException e) {
            Log.e(TAG, "Unable to read from serial device", e);
        }

        int displayTimeOffset =  readData[4] & 0xFF |
                (readData[5] & 0xFF) << 8 |
                (readData[6] & 0xFF) << 16 |
                (readData[7] & 0xFF) << 24;

        Log.d(TAG, "The devices Display Time Offset is " + displayTimeOffset);

        return  displayTimeOffset;
    }

    private byte[] getEGVDataPageRange(){
        int[] rets = new int[24];
        int c = 0;
        
        //EGVData page range read command
        byte[] readEGVDataPageRange = new byte[7];
        readEGVDataPageRange[0] = 0x01;
        readEGVDataPageRange[1] = 0x07;
        readEGVDataPageRange[3] = 0x10;
        readEGVDataPageRange[4] = 0x04;
        readEGVDataPageRange[5] = (byte)0x8b;
        readEGVDataPageRange[6] = (byte)0xb8;
        DexcomPacket p = new DexcomPacket(Constants.READ_DATABASE_PAGE_RANGE);
        ArrayList<Byte> dat = new ArrayList<Byte>();

        dat.add((byte)Constants.RECORD_TYPES.EGV_DATA.ordinal());

        byte[] z = p.Compose(dat);

        Log.i(TAG, (z == readEGVDataPageRange) + "ok?");
    
        try {
            rets[c++] = mSerialDevice.write(z, 200);
        } catch (IOException e) {
            Log.e(TAG, "Unable to write to serial device", e);
        }
        byte[] dexcomPageRange = new byte[256];
        try {
            rets[c++] = mSerialDevice.read(dexcomPageRange, 200);
        } catch (IOException e) {
            Log.e(TAG, "Unable to read from serial device", e);
        }
        
        return dexcomPageRange;
    }

    private byte[] getLastFourPages(byte [] dexcomPageRange, int pageOffset)
    {
        int[] rets = new int[24];
        int c = 0;
        byte [] endPage = new byte[]{dexcomPageRange[8], dexcomPageRange[9], dexcomPageRange[10], dexcomPageRange[11]};

        //ONLY interested in the last 4 pages of data for this app's requirements
        int endInt = toInt(endPage, 1);
        int lastFour = endInt - 4 * pageOffset + 1;

        //support for a receiver without any old data
        if (lastFour < 0) lastFour = 0;

        ByteBuffer b = ByteBuffer.allocate(4);
        b.putInt(lastFour);
        byte[] result = b.array();
          
        //Build get page (EGV) command
        byte [] getLastEGVPage = new byte[636];
        getLastEGVPage[0] = 0x01;
        getLastEGVPage[1] = 0x0c;
        getLastEGVPage[2] = 0x00;
        getLastEGVPage[3] = 0x11;
        getLastEGVPage[4] = 0x04;
        getLastEGVPage[5] = result[3];
        getLastEGVPage[6] = result[2];
        getLastEGVPage[7] = result[1];
        getLastEGVPage[8] = result[0];
        getLastEGVPage[9] = 0x04;
   
        //Get checksum
        int getLastEGVCRC = calculateCRC16(getLastEGVPage, 0, 10);
        byte crcByte1 = (byte) (getLastEGVCRC & 0xff);
        byte crcByte2 = (byte) ((getLastEGVCRC >> 8) & 0xff);

        getLastEGVPage[10] = crcByte1;
        getLastEGVPage[11] = crcByte2;

        try {
            rets[c++] = mSerialDevice.write(getLastEGVPage, 200);
        } catch (IOException e) {
            Log.e(TAG, "Unable to write to serial device", e);
        }
        
        //Get pages
        byte[] dexcomDatabasePages = new byte[2122];

        try {
            rets[c++] = mSerialDevice.read(dexcomDatabasePages, 20000);
        } catch (IOException e) {
            Log.e(TAG, "Unable to read from serial device", e);
        }

       //Parse pages
        byte [] databasePages = new byte[2112];
        System.arraycopy(dexcomDatabasePages, 4, databasePages, 0, 2112);
        return databasePages;        
    }

    private EGVRecord[] parseDatabasePages(byte[] databasePages) {

        byte [][] fourPages = new byte[4][528];
        int [] recordCounts = new int[4];
        int totalRecordCount = 0;
        
        //we parse 4 pages at a time, calculate total record count while we do this
        for (int i = 0; i < 4; i++)
        {
            System.arraycopy(databasePages, 528*i, fourPages[i], 0, 528);
            recordCounts[i] = fourPages[i][4];
            totalRecordCount = totalRecordCount + recordCounts[i];
        }
        
        EGVRecord[] recordsToReturn = new EGVRecord[totalRecordCount];
        int k = 0;
        
        //parse each record, plenty of room for improvement
        byte [] tempRecord = new byte[13];
        for (int i = 0; i < 4; i++)
        {
            for (int j = 0; j < recordCounts[i]; j++)
            {
                System.arraycopy(fourPages[i], 28 + j*13, tempRecord, 0, 13);

                byte [] eGValue = new byte[]{tempRecord[8],tempRecord[9]};

                int bGValue = ((eGValue[1]<<8) + (eGValue[0] & 0xff)) & 0x3ff;

                byte [] dateTime = new byte[]{tempRecord[7],tempRecord[6],tempRecord[5],tempRecord[4]};

                ByteBuffer buffer = ByteBuffer.wrap(dateTime);
                int dt = buffer.getInt();

                SimpleDateFormat f = new SimpleDateFormat("dd-MM-yyyy");
                Date d;
                try {
                    d = f.parse(EPOCH);
                } catch (ParseException e) {
                    Log.e(TAG, "Unable to parse date: " + EPOCH + ", using current time", e);
                    d = new Date();
                }

                // Epoch is PST, but but having epoch have user timezone added, then don't have to add to the
                // display time
                long milliseconds = d.getTime();

                long timeAdd = milliseconds + (1000L*dt);
                TimeZone tz = TimeZone.getDefault();

                if (tz.inDaylightTime(new Date()))
                    timeAdd = timeAdd - 3600000L;

                Date display = new Date(timeAdd);
                byte trendArrow = (byte) (tempRecord[10] & (byte)15);

                Constants.TREND_ARROW_VALUES arrow = Constants.TREND_ARROW_VALUES.values()[trendArrow];

                
                this.trend = arrow.friendlyTrendName();
                this.displayTime = new SimpleDateFormat("MM/dd/yyy hh:mm:ss aa").format(display);
                this.bGValue = String.valueOf(bGValue);

                EGVRecord record = new EGVRecord();
                record.setBGValue(this.bGValue);
                record.setDisplayTime(this.displayTime);
                record.setTrend(arrow.friendlyTrendName());
                record.setTrendArrow(arrow.Symbol());

                recordsToReturn[k++] = record;
            }
        }       
        return recordsToReturn;

    }

    //CRC methods
    public static int calculateCRC16 (byte [] buff, int start, int end) {

        int crc = 0;
        for (int i = start; i < end; i++)
        {

            crc = ((crc  >>> 8) | (crc  << 8) )& 0xffff;
            crc ^= (buff[i] & 0xff);
            crc ^= ((crc & 0xff) >> 4);
            crc ^= (crc << 12) & 0xffff;
            crc ^= ((crc & 0xFF) << 5) & 0xffff;

        }
        crc &= 0xffff;
        return crc;

    }
    
    //Convert the packet data
    public static int toInt(byte[] b, int flag) {
        switch(flag){
            case 0: //BitConverter.FLAG_JAVA:
                return (int)(((b[0] & 0xff)<<24) | ((b[1] & 0xff)<<16) | ((b[2] & 0xff)<<8) | (b[3] & 0xff));
            case 1: //BitConverter.FLAG_REVERSE:
                return (int)(((b[3] & 0xff)<<24) | ((b[2] & 0xff)<<16) | ((b[1] & 0xff)<<8) | (b[0] & 0xff));
            default:
                throw new IllegalArgumentException("BitConverter: toInt");
        }
    }

    @Override
    protected Object doInBackground(UsbSerialDriver... params) {

        return new String[]{displayTime, bGValue, trend};

    }
}
