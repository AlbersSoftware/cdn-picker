package com.cdnworkbench;

import com.cdnworkbench.ui.MainFrame;
import javax.swing.*;

public class Main {
    public static void main(String[] args) {
        System.out.printf("Java %s  |  Virtual threads ready%n",
                          System.getProperty("java.version"));
        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame();
            frame.setVisible(true);
        });
    }
}
