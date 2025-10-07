package de.bookwaves;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import de.feig.fedm.BrmItem;
import de.feig.fedm.DiagEventItem;
import de.feig.fedm.ErrorCode;
import de.feig.fedm.EventType;
import de.feig.fedm.IConnectListener;
import de.feig.fedm.IReaderListener;
import de.feig.fedm.InputEventItem;
import de.feig.fedm.ListenerParam;
import de.feig.fedm.PeerInfo;
import de.feig.fedm.ReaderIdentification;
import de.feig.fedm.ReaderModule;
import de.feig.fedm.ReaderStatus;
import de.feig.fedm.RequestMode;
import de.feig.fedm.RssiItem;
import de.feig.fedm.TagEventItem;
import de.feig.fedm.functionunit.ExtensionModule;
import de.feig.fedm.functionunit.Pdevice;
import de.feig.fedm.functionunit.PeopleCounterEventItem;
import de.feig.fedm.types.IntRef;
import de.feig.fedm.utility.HexConvert;

public class NotifyModeSampleNewReader_0x2B implements IConnectListener, IReaderListener
{
    
    // *********************************************************
    // SUPPORTED READERS
    // LR5400
    // LRU4000
    // MRU400
    // *********************************************************
    
    private ExtensionModule m_extensionModule;
    private ReaderModule m_reader;

    // *********************************************************
    // SETTINGS
    // *********************************************************
    private int port = 20001;           // Set Port-Number
    private String     ipAddr = "";          // (Default: any IPv4)
    private boolean keepAlive = true;  // Set Keep-Alive on/off
    private Lock lock = new ReentrantLock(true);
    // *********************************************************
    
    public NotifyModeSampleNewReader_0x2B() {
        m_reader = new ReaderModule(RequestMode.UniDirectional);
    }
    
    public void finalize() throws Throwable{
        m_reader.close();
        m_extensionModule.close();
    }
    
    public void startListener() {
        int state;
        try
        {
            lock.lock();
            m_reader = new ReaderModule(RequestMode.UniDirectional);

            m_extensionModule = new ExtensionModule(m_reader);
            
            state = m_reader.async().startNotification(this);
            System.out.println("startNotification: " + m_reader.lastErrorStatusText());
            if (state != ErrorCode.Ok) { /* Add error-handling... */ }

            state = m_reader.startListenerThread(ListenerParam.createTcpListenerParam(port, ipAddr, keepAlive), this);
            System.out.println("startListenerThread: " + m_reader.lastErrorStatusText());
            if (state != ErrorCode.Ok) { /* Add error-handling... */ }
        }
        finally {
            lock.unlock();
        }
    }
    
    
    public void waitforNotification()
    {
        System.out.println("Press any key to close");
        try
        {
            System.in.read();
        
        } catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    public void stopListener() {
        
        try {
            lock.lock();
            int state = m_reader.stopListenerThread();
            System.out.println("stopListenerThread: " + m_reader.lastErrorStatusText());
            if (state != ErrorCode.Ok) { /* Add error-handling... */ }
    
            state = m_reader.async().stopNotification();
            System.out.println("stopNotification: " + m_reader.lastErrorStatusText());
            if (state != ErrorCode.Ok) { /* Add error-handling... */ }
        }
        finally
        {
            lock.unlock();
        }
    }
    
    @Override
    public void onNewRequest()
    {
        try {
            lock.lock();
            IntRef stateRef = new IntRef(ErrorCode.Ok);
            EventType eventType = m_reader.async().popEvent(stateRef);
            int state = stateRef.getValue();
            if (state != ErrorCode.Ok) { System.out.println("Status of received notification: " + ReaderStatus.toString(state)); }
            while (eventType != EventType.Invalid)
            {
                System.out.println("Event: " + eventType.toString());
                 

                /*switch (eventType)
                {
                    case IdentificationEvent:
                        onNewIdentificationEvent();
                        break;
                    case InputEvent: 
                        onNewInputEvent();
                        break;
                    case DiagEvent: 
                        onNewDiagStatusEvent();
                        break;
                    case TagEvent: 
                        onNewTagEvent();
                        break;
                    case PeopleCounterEvent: 
                        onNewPeopleCounterEvent();
                        break;
                    default:
                        break;
                }*/
                eventType = m_reader.async().popEvent(stateRef);
                state = stateRef.getValue();
            }
        }
        finally {
            lock.unlock();
        }
    }

    @Override
    public void onConnect(PeerInfo peerInfo)
    {
        System.out.println("IConnectListener: Reader connected (" + peerInfo.ipAddress() + ":" + port + ")");
    }

    @Override
    public void onDisconnect()
    {
        System.out.println("IConnectListener: Reader disconnected");

    }
    
    void onNewIdentificationEvent()
    {
        ReaderIdentification readerIdent = m_reader.identification();
        if (readerIdent.isValid() )
        {
            System.out.println("*************************************");
            System.out.println("Identification");
            System.out.println("Reader device ID: " + m_reader.identification().deviceIdToHexString());
            System.out.println("Reader type: " + m_reader.identification().readerTypeToString());
            System.out.println("Firmware version: " + m_reader.identification().versionToString());
            System.out.println("*************************************");

        }
        else
        {
            System.out.println("Identification " + "not valid");
        }
    
    }

    void onNewDiagStatusEvent()
    {
        DiagEventItem readerDiag = m_reader.diagnostic().popItem();
        while (readerDiag != null)
        {
            System.out.println("*************************************");
            System.out.println("Report: "  +  readerDiag.toReport());
            System.out.println("*************************************");

            readerDiag = m_reader.diagnostic().popItem();
        }
    }

    void onNewInputEvent()
    {
        InputEventItem inItem = m_reader.io().popInItem();
        while (inItem != null)
        {
            System.out.println("*************************************");
            System.out.println("Input Event");
            System.out.println("Time:\t"  +  inItem.dateTime().toString());
            System.out.println("Current Input:\t"  +  inItem.currentInput());
            System.out.println("Previous Input:\t"  +  inItem.previousInput());
            System.out.println("*************************************");

            inItem = m_reader.io().popInItem();
        }
    }

    void onNewTagEvent()
    {
        TagEventItem tagEventItem = m_reader.tagEvent().popItem();
        while (tagEventItem != null)
        {
            System.out.println("*************************************");
            System.out.println("Tag Event");
            System.out.println(TagEventToString(tagEventItem));
            System.out.println("*************************************");

            tagEventItem = m_reader.tagEvent().popItem();
        }
    }

    void onNewPeopleCounterEvent()
    {
        PeopleCounterEventItem peopleCounterEventItem = m_extensionModule.pdevice().popPeopleCounterItem();
        while (peopleCounterEventItem != null)
        {
            if (peopleCounterEventItem.isValid()) {
                System.out.println("DetectorCounter 1/1: " + peopleCounterEventItem.detector1Counter1());
                System.out.println("DetectorCounter 1/2: " + peopleCounterEventItem.detector1Counter2());
                System.out.println("DetectorCounter 2/1: " + peopleCounterEventItem.detector2Counter1());
                System.out.println("DetectorCounter 2/2: " + peopleCounterEventItem.detector2Counter1());
                if (peopleCounterEventItem.dateTime().isValid())
                {
                    System.out.println("Date " + peopleCounterEventItem.dateTime().toString());
                }
                peopleCounterEventItem = m_extensionModule.pdevice().popPeopleCounterItem();
            }
            else
            {
                System.out.println("PeopleCounterEventItem " + "not valid");
            }
        }
    }
    
    public static String TagEventToString(TagEventItem tagEventItem)
    {
        StringBuilder tagEventItemPrint = new StringBuilder();

        // **************
        // Date
        // **************
        if (tagEventItem.dateTime().isValidDate())
        {
            int day = tagEventItem.dateTime().day();
            int month = tagEventItem.dateTime().month();
            int year = tagEventItem.dateTime().year();

            tagEventItemPrint.append("Date: " + year + "-" + month + "-" + day + "\n");
        }
        else
        {
            tagEventItemPrint.append("Date: " + "not valid" + "\n");
        }

        // **************
        // Time
        // **************

        if (tagEventItem.dateTime().isValidTime())
        {
            int hour = tagEventItem.dateTime().hour();
            int minute = tagEventItem.dateTime().minute();
            int second = tagEventItem.dateTime().second();
            int milliSecond = tagEventItem.dateTime().milliSecond();

            tagEventItemPrint.append("Time: " + hour + ":" + minute + ":" + second + "." + milliSecond + "\n");
        }
        else
        {
            tagEventItemPrint.append("Time: " + "not valid" + "\n");
        }

        // **************
        // IDD
        // **************
        if (tagEventItem.tag().isValid())
        {
            tagEventItemPrint.append("IDD: " + tagEventItem.tag().iddToHexString() + "\n");

            // ***************
            // RSSI + Antenna
            // ***************
            List<RssiItem> list = tagEventItem.tag().rssiValues();
            for (RssiItem rssiItem : list)
            {
                if (rssiItem.isValid())
                {
                    tagEventItemPrint.append("RSSI: " + rssiItem.rssi() + "\n");
                    tagEventItemPrint.append("Antenna: " + rssiItem.antennaNumber() + "\n");
                }
                else
                {
                    tagEventItemPrint.append("RSSI: " + "not valid" + "\n");
                    tagEventItemPrint.append("Antenna: " + "not valid" + "\n");
                }

                tagEventItemPrint.append("Phase Angle: " + rssiItem.phaseAngle() + "dec" + "\n");

            }
        }
        else
        {
            tagEventItemPrint.append("IDD: " + "not valid" + "\n");
        }

        // **************
        // Data
        // **************
        if (tagEventItem.dbUser().isValid())
        {
            tagEventItemPrint.append("Data: " + HexConvert.toHexString(tagEventItem.dbUser().blocks()) + "\n");
            tagEventItemPrint.append("Data blockCount: " + tagEventItem.dbUser().blockCount() + "\n");
            tagEventItemPrint.append("Data blockSize: " + tagEventItem.dbUser().blockSize() + "\n");
        }
        else
        {
            tagEventItemPrint.append("User Data Blocks not valid" + "\n");
        }

        if (tagEventItem.dbEpc().isValid())
        {
            tagEventItemPrint.append("Data: " + HexConvert.toHexString(tagEventItem.dbEpc().blocks()) + "\n");
            tagEventItemPrint.append("Data blockCount: " + tagEventItem.dbEpc().blockCount() + "\n");
            tagEventItemPrint.append("Data blockSize: " + tagEventItem.dbEpc().blockSize() + "\n");
        }
        else
        {
            tagEventItemPrint.append("EPC Data Blocks not valid" + "\n");
        }

        if (tagEventItem.dbTid().isValid())
        {
            tagEventItemPrint.append("Data: " + HexConvert.toHexString(tagEventItem.dbTid().blocks()) + "\n");
            tagEventItemPrint.append("Data blockCount: " + tagEventItem.dbTid().blockCount() + "\n");
            tagEventItemPrint.append("Data blockSize: " + tagEventItem.dbTid().blockSize() + "\n");
        }
        else
        {
            tagEventItemPrint.append("TID Data Blocks not valid" + "\n");
        }

        // **************
        // AFI
        // **************
        if (tagEventItem.tag().iso15693_IsValidAfi())
        {
            tagEventItemPrint.append("AFI : " + tagEventItem.tag().iso15693_Afi() + "\n");
            tagEventItemPrint.append("new AFI : " + tagEventItem.tag().iso15693_NewAfi() + "\n");
        }
        else
        {
            tagEventItemPrint.append("AFI: " + "not valid" + "\n");
        }
        
        // **************
        // Input
        // **************

        if (tagEventItem.input().isValid())
        {
            tagEventItemPrint.append("Input: " + tagEventItem.input().input() + "\n");
            tagEventItemPrint.append("State: " + tagEventItem.input().state() + "\n");
        }
        else
        {
            tagEventItemPrint.append("Input: " + "not valid" + "\n");
        }


        // **************
        // Direction
        // **************

        if (tagEventItem.direction().isValid())
        {
            tagEventItemPrint.append("Sector Direction: " + "Direction " + tagEventItem.direction().direction() + "\n");
        }
        else
        {
            tagEventItemPrint.append("Sector Direction: " + "not valid" + "\n");
        }

        // **************
        // EAS Alarm
        // **************

        if (!tagEventItem.evtSignals().isEmpty())
        {
            tagEventItemPrint.append("EAS Alarm: " + tagEventItem.evtSignals().isEasAlarm() + "\n");
        }
        else
        {
            tagEventItemPrint.append("No EAS Alarm" + "\n");
        }

        return tagEventItemPrint.toString();
    }
}