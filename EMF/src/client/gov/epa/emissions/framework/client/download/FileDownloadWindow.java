package gov.epa.emissions.framework.client.download;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;

import org.apache.hc.client5.http.async.methods.AbstractCharResponseConsumer;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpResponse;
import org.apache.hc.core5.http.message.BasicHttpRequest;
import org.apache.hc.core5.http.message.StatusLine;
import org.apache.hc.core5.http.nio.support.BasicRequestProducer;
import org.apache.hc.core5.http.support.BasicRequestBuilder;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.util.Timeout;

import gov.epa.emissions.commons.security.User;
import gov.epa.emissions.commons.util.CustomDateFormat;
import gov.epa.emissions.framework.client.DisposableInteralFrame;
import gov.epa.emissions.framework.client.console.DesktopManager;
import gov.epa.emissions.framework.client.console.EmfConsole;
import gov.epa.emissions.framework.client.cost.controlstrategy.AnalysisEngineTableApp;
import gov.epa.emissions.framework.client.swingworker.GenericSwingWorker;
import gov.epa.emissions.framework.services.EmfException;
import gov.epa.emissions.framework.services.basic.FileDownload;
import gov.epa.emissions.framework.ui.ImageResources;
import gov.epa.emissions.framework.ui.MessagePanel;
import gov.epa.emissions.framework.ui.RefreshButton;
import gov.epa.emissions.framework.ui.RefreshObserver;
import gov.epa.emissions.framework.ui.SingleLineMessagePanel;

public class FileDownloadWindow 
  extends DisposableInteralFrame 
  implements FileDownloadView, RefreshObserver {

    private MessagePanel messagePanel;

    private FileDownloadTableModel fileDownloadTableModel;

    private FileDownloadPresenter presenter;

    private EmfConsole parent;
    private JTable table = null;
    private Task task;

    private CountDownLatch latch = new CountDownLatch(2);
    private ExecutorService executor = Executors.newFixedThreadPool(2);

    private User user;
    
    private String downloadFolder;

    private RefreshButton refreshButton;
   
    
    class Task extends SwingWorker<Void, Void> {
        private CountDownLatch latch;
        private FileDownload fileDownload;

        public Task(FileDownload fileDownload, CountDownLatch latch) {
            this.latch = latch;
            this.fileDownload = fileDownload;
        }

        /*
         * Main task. Executed in background thread.
         */
        @Override
        public Void doInBackground() throws FileNotFoundException {
//            System.out.println("check to see if file download is already there");
//            System.out.println("if overwrite flag is true then overwrite the file...");
            
            
            int progress = 0;
//            fileDownload.setProgress(0);

            String downloadURL = fileDownload.getUrl();
            CloseableHttpAsyncClient httpclient = null;
            File downloadedFile = null;
            boolean downloadIssue = false;
            try {
                if (downloadFolder == null) 
                    downloadFolder = presenter.getDownloadFolder();
                downloadedFile = new File(downloadFolder + "//" + fileDownload.getFileName());
                
                //check to see if file download is already there
                if (downloadedFile.exists() && !fileDownload.getOverwrite()) {
                    fileDownload.setMessage("File has already been downloaded.");
                    return null;
                }
                
                final IOReactorConfig ioReactorConfig = IOReactorConfig.custom()
                        .setSoTimeout(Timeout.ofSeconds(10))
                        .build();
                
                httpclient = HttpAsyncClients
                        .custom()
                        .setIOReactorConfig(ioReactorConfig)
                        .setDefaultRequestConfig(RequestConfig.custom()
                                .setConnectTimeout(Timeout.ofSeconds(10))
                                .setResponseTimeout(Timeout.ofSeconds(10))
                                .build())
                        .build();

                //Initialize progress property.
                setProgress(0);
            } catch (RuntimeException e2) {
                // NOTE Auto-generated catch block
                e2.printStackTrace();
            } catch (EmfException e) {
                // NOTE Auto-generated catch block
                e.printStackTrace();
            }

            //final FileChannel fileOutputStream = new FileOutputStream(downloadedFile, false).getChannel();
OutputStreamWriter outputStreamWriter = new OutputStreamWriter(new FileOutputStream(downloadedFile, false), StandardCharsets.UTF_8);
 
//            System.out.println("start file download...");
            
//            httpclient.getParams()
//                .setIntParameter(CoreConnectionPNames.SO_TIMEOUT, 10000)
//                .setIntParameter(CoreConnectionPNames.CONNECTION_TIMEOUT, 10000)
//                .setIntParameter(CoreConnectionPNames.SOCKET_BUFFER_SIZE, 8 * 1024)
//                .setBooleanParameter(CoreConnectionPNames.TCP_NODELAY, true)
//                .setIntParameter(CoreConnectionPNames.MIN_CHUNK_LIMIT, 8 * 1024);
            httpclient.start();
            try {
                  
                // Create a writable file channel
//                wChannel = new FileOutputStream(downloadedFile, false).getChannel();

//                ZeroCopyConsumer<File> consumer = new ZeroCopyConsumer<File>(downloadedFile) {
//
//                    long position = 0;
////                        @Override
////                        protected void onResponseReceived(HttpResponse httpResponse) {
////                            
////                        }
//                    
//                    long downloadedBytes = 0;
//                    int previousProgress = 0;
//                    int progress = 0;
//
//                    @Override
//                    protected void onContentReceived(org.apache.http.nio.ContentDecoder decoder, org.apache.http.nio.IOControl ioctrl)  {
//                        boolean allRead = false;
//                        ByteBuffer t = ByteBuffer.allocate(2048);
//
//                        while(!allRead) {
//                          int count = 0;
//                        try {
//                            count = decoder.read(t);
//                        } catch (IOException e1) {
//                            // NOTE Auto-generated catch block
//                            e1.printStackTrace();
//                        }
//                          if(count <= 0) {
//                            allRead = true;
////                                System.out.println("Buffer reading is : " + decoder.isCompleted());
//                          } else {
//                              downloadedBytes += count;
////                                  System.out.println("onContentReceived downloadedBytes = " + downloadedBytes + ", count = " + count);
////                                  System.out.println("****** Number of Bytes read is : " + count);
//                             t.flip();
//                             try {
//                                wChannel.write(t);
//                            } catch (IOException e) {
//                                // NOTE Auto-generated catch block
//                                e.printStackTrace();
//                            }
//                            t.clear();
//                          }
//                          
//                        }
//                        if (fileDownload.getSize() != 0) 
//                            progress = Math.min((int)((downloadedBytes * 100.0) / fileDownload.getSize()), 100);
//                        if (previousProgress != progress) {
//                            setProgress(progress);
////                                fileDownload.setProgress(progress);
////                                table.repaint();
//                        }
//                        previousProgress = progress;
//                    }
//                    
//                    @Override
//                    protected File process(
//                            final HttpResponse response, 
//                            final File file,
//                            final ContentType contentType) throws Exception {
////                            System.out.println("process");
//                        if (response.getStatusLine().getStatusCode() != HttpStatus.SC_OK) {
//                            throw new ClientProtocolException("Upload failed: " + response.getStatusLine());
//                        }
//                        //finalize progress bar download status
//                        setProgress(100);
////                            fileDownload.setProgress(100);
////                            table.repaint();
//                        return file;
//                    }
//
//                };

                final BasicHttpRequest request = BasicRequestBuilder.get()
                        .setUri(downloadURL)
                        .build();


                
                final Future<File> future = httpclient.execute(
                        new BasicRequestProducer(request, null),
                        new AbstractCharResponseConsumer<File>() {

                            long position = 0;
                            long downloadedBytes = 0;
                            int previousProgress = 0;
                            int progress = 0;

                            @Override
                            protected void start(
                                    final HttpResponse response,
                                    final ContentType contentType) throws HttpException, IOException {
                                System.out.println(request + "->" + new StatusLine(response));
                            }

                            @Override
                            protected int capacityIncrement() {
                                return Integer.MAX_VALUE;
                            }

                            @Override
                            protected void data(final CharBuffer data, final boolean endOfStream) throws IOException {
//                                while (data.hasRemaining()) {
//                                    System.out.print(data.get());
//                                }
//                                if (endOfStream) {
//                                    System.out.println();
//                                }

                                boolean allRead = false;
//                                ByteBuffer t = ByteBuffer.allocate(data.length() * Character.BYTES);
//                                ByteBuffer result = ByteBuffer.allocate(data.length() * Character.BYTES);
//                                CharBuffer converter = result.asCharBuffer();
//                                converter.append(data);

                                
//                                while(!allRead) {
                                  int count = 0;
//                                try {
                                    count = data.length();
//                                } catch (IOException e1) {
//                                    // NOTE Auto-generated catch block
//                                    e1.printStackTrace();
//                                }
                                  if(count <= 0) {
                                    allRead = true;
//                                        System.out.println("Buffer reading is : " + decoder.isCompleted());
                                  } else {
                                      downloadedBytes += count;
//                                          System.out.println("onContentReceived downloadedBytes = " + downloadedBytes + ", count = " + count);
//                                          System.out.println("****** Number of Bytes read is : " + count);
//                                     t.flip();
                                     try {
                                         outputStreamWriter.append(data);
                                    } catch (IOException e) {
                                        // NOTE Auto-generated catch block
                                        e.printStackTrace();
                                    }
//                                    t.clear();
                                  }
                                  
//                                }
                                if (fileDownload.getSize() != 0) 
                                    progress = Math.min((int)((downloadedBytes * 100.0) / fileDownload.getSize()), 100);
                                if (previousProgress != progress) {
                                    setProgress(progress);
//                                        fileDownload.setProgress(progress);
//                                        table.repaint();
                                }
                                previousProgress = progress;
                            }

                            @Override
                            protected File buildResult() throws IOException {
                                return null;
                            }

                            @Override
                            public void failed(final Exception cause) {
                                System.out.println(request + "->" + cause);
                            }

                            @Override
                            public void releaseResources() {
                            }

                        }, null);
                future.get();
                //asynchronously download the file
//                System.out.println("downloadURL = " + downloadURL);
//                Future<File> future = httpclient.execute(HttpAsyncMethods.createGet(downloadURL), 
//                        consumer, 
//                        new FutureCallback<File>() {
//
//                            public void cancelled() {
//                                // NOTE Auto-generated method stub
////                                System.out.println("cancelled");
//                            }
//
//                            public void completed(File arg0) {
//                                // NOTE Auto-generated method stub
////                                System.out.println("completed");
//                            }
//
//                            public void failed(Exception arg0) {
//                                // NOTE Auto-generated method stub
////                                System.out.println("failed");
//                            }
//                        });
//                File result = future.get();
//                System.out.println("Response file length: " + result.length());
//                System.out.println("Response file length: " + result.getAbsolutePath());
                
//                    System.out.println("consumer.isDone() = " + consumer.isDone());
//                System.out.println("Shutting down");
            } catch (InterruptedException e) {
                // NOTE Auto-generated catch block
                e.printStackTrace();
                downloadIssue = true;
            } catch (ExecutionException e) {
                // NOTE Auto-generated catch block
                e.printStackTrace();
                downloadIssue = true;
            } finally {
                try {
                   outputStreamWriter.close();
                } catch (IOException e) {
                    // NOTE Auto-generated catch block
                    e.printStackTrace();
                }
                try {
                    httpclient.close();
                } catch (IOException e) {
                    // NOTE Auto-generated catch block
                    e.printStackTrace();
                }
                if (downloadIssue)
                    try {
                        //get rid of file, its not needed since there was a download issue
                        downloadedFile.delete();
                    } catch (Exception e) {
                        // NOTE Auto-generated catch block
                        e.printStackTrace();
                    }
            }
//            System.out.println("Done");
            return null;
        }

        /*
         * Executed in event dispatching thread
         */
        @Override
        public void done() {
//            Toolkit.getDefaultToolkit().beep();
//            startButton.setEnabled(true);
            setCursor(null); //turn off the wait cursor
            latch.countDown();
//            taskOutput.append("Done!\n");
        }
    }

    public FileDownloadWindow(EmfConsole parent, DesktopManager desktopManager, User user) {
        super("Downloads", desktopManager);
        super.setName("Downloads");
        this.parent = parent;
        this.user = user;
        position(parent);
        super.setContentPane(createLayout());

        super.setClosable(false);
        super.setMaximizable(false);
    }

    private JPanel createLayout() {
        JPanel layout = new JPanel();
        layout.setLayout(new BorderLayout());

        layout.add(createTopPanel(), BorderLayout.NORTH);
        layout.add(createTable(), BorderLayout.CENTER);

        return layout;
    }

    private JPanel createTopPanel() {
        JPanel panel = new JPanel(new BorderLayout());

        JPanel container = new JPanel(new FlowLayout());
        messagePanel = new SingleLineMessagePanel(false);
        container.add(messagePanel);

        JButton clearButton = createClearButton();
        getRootPane().setDefaultButton(clearButton);
        container.add(clearButton);

        JButton goToFolderButton = createGoToFolderButton();
        container.add(goToFolderButton);

        refreshButton = createRefreshButton();
        container.add(refreshButton);

        panel.add(container, BorderLayout.EAST);

        return panel;
    }

    private JButton createClearButton() {
        JButton button = new JButton("Clear");
        button.setIcon(trashIcon());
        button.setBorderPainted(false);
        button.setToolTipText("Removes downloads from the list");
        button.setMnemonic(KeyEvent.VK_C);

        button.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                presenter.doClear();
            }
        });

        return button;
    }

    private JButton createGoToFolderButton() {
        JButton button = new JButton("Go to Folder");
        button.setBorderPainted(false);
        button.setToolTipText("Go to folder containing download files");
        button.setMnemonic(KeyEvent.VK_G);

        button.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                openContaingFolder();
            }
        });

        return button;
    }

    private RefreshButton createRefreshButton() {
        return new RefreshButton(this, "Refresh the Downloaded Files", messagePanel);
    }

    private ImageIcon trashIcon() {
        return new ImageResources().trash("Clear Downloaded Files");
    }

    private JScrollPane createTable() {

        fileDownloadTableModel = new FileDownloadTableModel();
        table = new JTable(fileDownloadTableModel);
//            new MultiLineTable(fileDownloadTableModel);
        table.setName("fileDownloads");
        table.getAccessibleContext().setAccessibleName("List of downloaded files");
        // FIXME: code put in for the demo
//        table.setRowHeight(50);
        //table.setDefaultRenderer(Object.class, new TextAreaTableCellRenderer());

        table.setCellSelectionEnabled(true);
//        MultiLineCellRenderer multiLineCR = new MultiLineCellRenderer();
        FileDownloadTableCellRenderer progressBarTableCellRenderer = new FileDownloadTableCellRenderer();
//        table.getColumnModel().getColumn(0).setCellRenderer(multiLineCR);
//        table.getColumnModel().getColumn(1).setCellRenderer(multiLineCR);
//        table.getColumnModel().getColumn(2).setCellRenderer(multiLineCR);
        table.getColumnModel().getColumn(0).setCellRenderer(progressBarTableCellRenderer);

        table.addMouseListener(new MouseListener() {
            
            public void mouseReleased(MouseEvent e) {
                int r = table.rowAtPoint(e.getPoint());
                int c = table.columnAtPoint(e.getPoint());
                if (r >= 0 && r < table.getRowCount()
                        && c >= 0 && c < table.getColumnCount()) {
                    table.setRowSelectionInterval(r, r);
                    table.setColumnSelectionInterval(c, c);
                } else {
                    table.clearSelection();
                }
                checkForPopup( e );
            }
            
            public void mousePressed(MouseEvent e) {
                checkForPopup( e );
            }
            
            public void mouseExited(MouseEvent e) {
                // NOTE Auto-generated method stub
                
            }
            
            public void mouseEntered(MouseEvent e) {
                // NOTE Auto-generated method stub
                
            }
            
            public void mouseClicked(MouseEvent e) {
                // NOTE Auto-generated method stub
                
            }
        });
        
//        setColumnWidths(table.getColumnModel());
//        table.setPreferredScrollableViewportSize(this.getSize());

        return new JScrollPane(table);
    }

    private void checkForPopup(MouseEvent e)
    {
        if (e.isPopupTrigger())
        {
            showPopup(e.getPoint());//table.columnAtPoint(e.getPoint()), table.rowAtPoint(e.getPoint()));
        }
    }

    /**
     *  Show a hidden column in the table.
     *
     *  @param  table        the table to which the column is added
     *  @param  columnName   the column name from the TableModel
     *                       of the column to be added
     */
    public void showPopup(final Point point) //int column, int row)
    {

        JPopupMenu popup = new JPopupMenu()
        {
            public void setSelected(Component sel)
            {
                int index = getComponentIndex( sel );
                getSelectionModel().setSelectedIndex(index);
                final MenuElement me[] = new MenuElement[2];
                me[0]=(MenuElement)this;
                me[1]=getSubElements()[index];
 
                SwingUtilities.invokeLater(new Runnable()
                {
                    public void run()
                    {
                        MenuSelectionManager.defaultManager()
                            .setSelectedPath(me);
                    }
                });
            }
        };
 
        //int columns = table.getModel().getColumnCount();
//        JMenuItem[] items = new JMenuItem[3];
//        JMenuItem item = new JMenuItem( "Download");
//        item.addActionListener(new ActionListener() {
//            
//            public void actionPerformed(ActionEvent e) {
//                final int selectedRow = table.getSelectedRow();
//                
//                System.out.println("selectedRow = " + selectedRow);
////                downloadFile(e.getSource());
//                // NOTE Auto-generated method stub
////                System.out.println("Popup Download click"  + e.getSource());
////                System.out.println(table.getModel().getValueAt(table.getSelectedRow(), 1));
////                System.out.println(((FileDownloadTableModel)(table.getModel())).getSource(table.getSelectedRow()));
//                final FileDownload fileDownload = (FileDownload) ((FileDownloadTableModel)(table.getModel())).getSource(selectedRow);
////                System.out.println(((FileDownloadTableModel)(table.getModel())).getSource(table.getSelectedRow()) instanceof FileDownload);
//                
//                //see if file is already downloaded...
//                if (fileDownload.getProgress() == 100) {
//                    messagePanel.setError("File is already downloaded.");
//                    return;
//                }
//                    
//                
//                fileDownload.setProgress(0);
//                
//                fileDownloadTableModel.setValueAt(fileDownload, selectedRow, 0);
////                presenter.doRefresh();
////                ((FileDownloadTableCellRenderer)table.getCellRenderer(selectedRow, 0)).setValue(fileDownload);
//
//                //Instances of javax.swing.SwingWorker are not reusuable, so
//                //we create new instances as needed.
//                task = new Task(fileDownload, latch);
//                task.addPropertyChangeListener(new PropertyChangeListener() {
//                    
//                    public void propertyChange(PropertyChangeEvent evt) {
////                        System.out.println(evt.getPropertyName() + " " + evt.getNewValue());
//                        if ("progress" == evt.getPropertyName()) {
//                            int progress = (Integer) evt.getNewValue();
//                            fileDownload.setProgress(progress);
//                            table.repaint();
////                            fileDownloadTableModel.setValueAt(fileDownload, selectedRow, 0);
////                            ((FileDownloadTableCellRenderer)table.getCellRenderer(selectedRow, 0)).setValue(fileDownload);
////                            progressBar.setValue(progress);
////                            taskOutput.append(String.format(
////                                    "Completed %d%% of task.\n", task.getProgress()));
//                        } 
//                    }
//                });
////                task.execute();
//                executor.execute(task);
//                fileDownload.setProgress(0);
//                
//                System.out.println("start new swingworkertask");  
//            }
//        });
//        popup.add( item );
//        //a group of radio button menu items
//        popup.addSeparator();
        JMenuItem item = new JMenuItem( "View in Analysis Engine");
        item.addActionListener(new ActionListener() {
            
            public void actionPerformed(ActionEvent e) {
                java.awt.EventQueue.invokeLater(new Runnable() {
                    public void run() {
                        final int selectedRow = table.getSelectedRow();
                        
//                        System.out.println("selectedRow = " + selectedRow);
//                        downloadFile(e.getSource());
                        // NOTE Auto-generated method stub
//                        System.out.println("Popup Download click"  + e.getSource());
//                        System.out.println(table.getModel().getValueAt(table.getSelectedRow(), 1));
//                        System.out.println(((FileDownloadTableModel)(table.getModel())).getSource(table.getSelectedRow()));
                        FileDownload fileDownload = (FileDownload) ((FileDownloadTableModel)(table.getModel())).getSource(selectedRow);
                        if (fileDownload.getProgress() == 100) {
                            AnalysisEngineTableApp app = new AnalysisEngineTableApp("View Exported File: " + fileDownload.getFileName(), new Dimension(500, 500), desktopManager, parent);
                            app.display(new String[] { downloadFolder + "/" + fileDownload.getFileName() });
                        } else {
                            // NOTE Auto-generated catch block
                            messagePanel.setError("File has not been fully downloaded.");
                        }
                    }
                });
            }
        });
        popup.add( item );
        item = new JMenuItem( "Open Containing Folder");
        item.addActionListener(new ActionListener() {
            
            public void actionPerformed(ActionEvent e) {
                java.awt.EventQueue.invokeLater(new Runnable() {
                    public void run() {
                        // NOTE Auto-generated method stub
                        openContaingFolder();

                    }
                });
                
            }
        });
        popup.add( item );

        //        for (int i = 0; i < items.length; i++)
//        {
//            if (items[i] != null)
//            {
//                popup.add( items[i] );
//            }
//        }
 
        //  Display the popup below the click point
        popup.show(table.getComponentAt(point), point.x, point.y);
    }

    private void openContaingFolder() {
        //                        System.out.println("Popup Open Containing Folder click");
        Desktop desktop = null;
        if (Desktop.isDesktopSupported()) {
            desktop = Desktop.getDesktop();
        }

        try {
            desktop.open(new File(downloadFolder));
        } catch (NullPointerException ex) {
            messagePanel.setMessage("Missing valid download folder");
        } catch (IOException ex) {
            messagePanel.setError(ex.getMessage());
        }
    }

    private void setColumnWidths(TableColumnModel model) {
        TableColumn message = model.getColumn(0);
        message.setPreferredWidth((int) (getWidth() * 0.75));
    }

    private void position(Container parent) {
        Dimension parentSize = parent.getSize();

        int width = (int) parentSize.getWidth() * 7 / 16 - 20;
        int height = 250;
        super.dimensions(width, height);
        super.setMinimumSize(new Dimension(width / 15, height));

        int x = 0;
        x = (int) parentSize.getWidth() - width - 20;
        int y = (int) parentSize.getHeight() - height - 90 - 150;
        setLocation(x, y);
    }

    public void disposeView() {
        super.dispose();
        // don't try to unregister, since we didn't register with the desktopManager
    }

    public void display() {
        setVisible(true);

        new GenericSwingWorker<FileDownload[]>(getContentPane(), messagePanel) {

            @Override
            public FileDownload[] doInBackground() throws EmfException {
                FileDownload[] fileDownloads = presenter.getFileDownloads(user.getId());
                presenter.markFileDownloadRead(buildFileDownloadIdList(fileDownloads));
                downloadFolder = presenter.getDownloadFolder();
//                System.out.println("downloadFolder = " + downloadFolder);
                //see if the file has already been downloaded
                for (FileDownload fileDownload : fileDownloads) {
                    File file = new File(downloadFolder + "//" + fileDownload.getFileName());
                    if (file.exists() && file.length() == fileDownload.getSize()) 
                        fileDownload.setProgress(100);
//                    if (Files.exists(FileSystems.getDefault().getPath(downloadFolder, fileDownload.getFileName()), new LinkOption[]{LinkOption.NOFOLLOW_LINKS})) {
//                        File file = new File(downloadFolder + "/" + fileDownload.getFileName());
//                        if (file.length() == fileDownload.getSize()) 
//                            fileDownload.setProgress(100);
//                    }
                }
                return fileDownloads;
            }
            
            private Integer[] buildFileDownloadIdList(FileDownload[] fileDownloads) {
                Integer[] fileDownloadIds = new Integer[fileDownloads.length];
                for (int i = 0; i < fileDownloads.length; i++) {
                    fileDownloadIds[i] = fileDownloads[i].getId();
                }
                return fileDownloadIds;
            }
            
            @Override
            public void done() {
                try {
                    //make sure something didn't happen
                    update(get());

                } catch (InterruptedException e1) {
                    messagePanel.setError(e1.getMessage());
//                    setErrorMsg(e1.getMessage());
                } catch (ExecutionException e1) {
                    messagePanel.setError(e1.getMessage());
//                    setErrorMsg(e1.getCause().getMessage());
                } finally {
                    super.finalize();
                }
            }
        }.execute();
}

    public void update(FileDownload[] fileDownloads) {
        messagePanel.setMessage("Last Update : " + CustomDateFormat.format_MM_DD_YYYY_HH_mm_ss(new Date()), Color.GRAY);
        fileDownloadTableModel.refresh(fileDownloads);
        for (int i = 0; i < fileDownloadTableModel.getRowCount(); i++) {
//            System.out.println(i);
            table.setRowHeight(i, 50);
        }

        for (final FileDownload fileDownload : fileDownloads) {
            if (fileDownload.getProgress() < 100 && !fileDownload.getRead()) {
                fileDownload.setProgress(0);
                
                //Instances of javax.swing.SwingWorker are not reusuable, so
                //we create new instances as needed.
                task = new Task(fileDownload, latch);
                task.addPropertyChangeListener(new PropertyChangeListener() {
                    
                    public void propertyChange(PropertyChangeEvent evt) {
        //                System.out.println(evt.getPropertyName() + " " + evt.getNewValue());
                        if ("progress" == evt.getPropertyName()) {
                            int progress = (Integer) evt.getNewValue();
                            fileDownload.setProgress(progress);
                            table.repaint();
                        } 
                    }
                });
                executor.execute(task);
                fileDownload.setProgress(0);
                //run not on EventDispatch
                new SwingWorker<Void, Void>() {

                    @Override
                    protected Void doInBackground() {
                        try {
                            presenter.markFileDownloadRead(fileDownload);
                        } catch (EmfException e) {
                            messagePanel.setError(e.getMessage());
                        }
                        return null;
                    }
                    
                }.execute();
            }        
        }
        
        
        if (fileDownloads.length > 0)
            this.toFront();
        super.revalidate();
    }

    public void notifyError(String message) {
        messagePanel.setError(message);
    }

    public void observe(FileDownloadPresenter presenter) {
        this.presenter = presenter;
    }

    public void clear() {
        parent.clearMesagePanel();
        fileDownloadTableModel.clear();
    }

    public void doRefresh() {
        presenter.doRefresh();
    }

    @Override
    public void refresh() {
//        fileDownloads = presenter.getUnreadFileDownloads(this.user.getId());
        new GenericSwingWorker<FileDownload[]>(getContentPane(), messagePanel) {

            @Override
            public FileDownload[] doInBackground() throws EmfException {
                return presenter.getUnreadFileDownloads(user.getId());
            }
            
            @Override
            public void done() {
                try {
                    //make sure something didn't happen
                    update(get());

                } catch (InterruptedException e1) {
                    messagePanel.setError(e1.getMessage());
//                    setErrorMsg(e1.getMessage());
                } catch (ExecutionException e1) {
                    messagePanel.setError(e1.getMessage());
//                    setErrorMsg(e1.getCause().getMessage());
                } finally {
                    super.finalize();
                    if (isSelected())
                        refreshButton.requestFocusInWindow();
                }
            }
        }.execute();
//      service.markFileDownloadsRead(buildFileDownloadIdList(fileDownloads));
    }
}
