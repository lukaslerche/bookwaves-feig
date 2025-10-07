package de.bookwaves;

import de.feig.fedm.Connector;
import de.feig.fedm.ErrorCode;
import de.feig.fedm.ReaderModule;
import de.feig.fedm.RequestMode;
import de.feig.fedm.exception.FedmRuntimeException;

public class NotiTest {
     public static void main(String[] args)
    {

        var nfs = new NotifyModeSampleNewReader_0x2B();
        nfs.startListener();
        //nfs.waitforNotification();
        try {
            Thread.sleep(20000);
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        nfs.stopListener();
        


        /* 
        // Create Reader Module with Request Mode UniDirectional = Advanced Protocol
        // Using try with to auto close the ReaderModule
        try (ReaderModule reader = new ReaderModule(RequestMode.UniDirectional))
        {
            // Create Connector Object (TCP)
            String ipAddr = "192.168.1.225";
            int port = 10001;
            Connector connector = Connector.createTcpConnector(ipAddr, port);

            // Connect TCP-Reader
            System.out.println("Start connection with Reader: " + connector.tcpIpAddress());
            reader.connect(connector);

            // Error handling
            if (reader.lastError() != ErrorCode.Ok)
            {
                System.out.println("Error while Connecting: " + reader.lastError());
                System.out.println(reader.lastErrorStatusText());
                return;
            }

            // Output ReaderType
            System.out.println("Reader " + reader.info().readerTypeToString() + " connected.\n");

            // ********************************************************************************
            // add Sample Code here
            // ********************************************************************************

            // Disconnect Reader
            reader.disconnect();

            if (reader.lastError() == ErrorCode.Ok)
            {
                System.out.println("\n" + reader.info().readerTypeToString() + " disconnected.");
            }
        }

        catch (FedmRuntimeException e)
        {
            System.out.println(e.getMessage());
        }*/
    }
}
