package java_api_calls;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.FileNotFoundException;


public class MainImpl {

    public static void increment(String counterFile) {
        int count = -1;

        // Read
        try (FileInputStream fis = new FileInputStream(counterFile)) {
            count = fis.read();
        } catch (FileNotFoundException fnfe) {
            fnfe.printStackTrace();
            System.exit(-1);
        } catch (IOException ioe) {
            ioe.printStackTrace();
            System.exit(-1);
        }

        // Increment
        ++count;

        // Write
        try (FileOutputStream fos = new FileOutputStream(counterFile)) {
            fos.write(count);
        } catch (FileNotFoundException fnfe) {
            fnfe.printStackTrace();
            System.exit(-1);
        } catch (IOException ioe) {
            ioe.printStackTrace();
            System.exit(-1);
        }

        // Sleep to check if API Barrier is working fine
        try {
            Thread.sleep(3_000);
        } catch (InterruptedException e) {
            // No need to handle such exception
        }
    }

}
