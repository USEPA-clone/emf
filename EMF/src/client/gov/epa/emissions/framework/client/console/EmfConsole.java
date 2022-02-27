package gov.epa.emissions.framework.client.console;

import gov.epa.emissions.commons.gui.Confirm;
import gov.epa.emissions.framework.ConcurrentTaskRunner;
import gov.epa.emissions.framework.client.EmfFrame;
import gov.epa.emissions.framework.client.EmfSession;
import gov.epa.emissions.framework.client.Label;
import gov.epa.emissions.framework.client.data.dataset.DatasetsBrowserPresenter;
import gov.epa.emissions.framework.client.data.dataset.DatasetsBrowserView;
import gov.epa.emissions.framework.client.data.dataset.DatasetsBrowserWindow;
import gov.epa.emissions.framework.client.download.FileDownloadPresenter;
import gov.epa.emissions.framework.client.download.FileDownloadWindow;
import gov.epa.emissions.framework.client.sms.SectorScenarioDialog;
import gov.epa.emissions.framework.client.sms.SectorScenarioPresenter;
import gov.epa.emissions.framework.client.status.StatusPresenter;
import gov.epa.emissions.framework.client.status.StatusWindow;
import gov.epa.emissions.framework.services.EmfException;
import gov.epa.emissions.framework.ui.Dimensions;
import gov.epa.emissions.framework.ui.MessagePanel;
import gov.epa.emissions.framework.ui.SingleLineMessagePanel;
import gov.epa.emissions.framework.ui.YesNoDialog;
import gov.epa.mims.analysisengine.table.TableApp;

import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.net.URL;

import javax.swing.JDesktopPane;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.ToolTipManager;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;

//FIXME: split this class up into smaller ones...getting too big

public class EmfConsole extends EmfFrame implements EmfConsoleView {

    private MessagePanel messagePanel;

    private WindowMenuPresenter windowMenuPresenter;

    private ManageMenu manageMenu;

    private StatusPresenter presenter;

    private FileDownloadPresenter fileDownloadPresenter;
    
    private static String guideUrl = "https://www.cmascenter.org/cost/documentation/3.7/EMF%20User's%20Guide/";
    
    private static String guideMessage = "<html><center>Please visit<br>" +
            "https://www.cmascenter.org/cost/<br>" +
            "and click the DOCUMENTATION link<br>" +
            "to view the EMF User's Guide</center></html>";

    private static String aboutMessage = "<html><center>Emissions Modeling Framework (EMF)<br>"
            + gov.epa.emissions.framework.client.login.LoginWindow.EMF_VERSION + 
            "<br>Developed by the Institute for the Environment<br>"
            + "University of North Carolina at Chapel Hill</center></html>";

    private DesktopManager desktopManager;

    private Confirm emfConfirmDialog;

    private EmfSession session;

    public EmfConsole(EmfSession session) {
        super("EMF Console", "Emissions Modeling Framework (EMF):  " + session.user().getName() + " ("
                + session.user().getUsername() + ")," + "          Server (" + System.getProperty("emf.remote.host") + ")");
        this.session = session;

        setProperties();
        createLayout(session);
        showStatus();
        showFileDownload();
    }

    private void createLayout(EmfSession session) {
        WindowMenu windowMenu = createWindowMenu();

        this.desktopManager = createDesktopManager(windowMenu);
        this.windowMenuPresenter.setDesktopManager(desktopManager);

        super.setJMenuBar(createMenuBar(windowMenu, session));
    }

    private DesktopManager createDesktopManager(WindowMenu windowMenu) {
        JDesktopPane desktop = new JDesktopPane();
        desktop.setDragMode(JDesktopPane.OUTLINE_DRAG_MODE);
        this.setContentPane(desktop);

        return new DesktopManagerImpl(windowMenu, this, new EmfDesktopImpl(desktop));
    }

    private void showStatus() {
        StatusWindow status = new StatusWindow(this, desktopManager, session.user());
        windowMenuPresenter.addPermanently(status);

        presenter = new StatusPresenter(session.dataCommonsService(), new ConcurrentTaskRunner());
        presenter.display(status);
    }

    private void showFileDownload() {
        FileDownloadWindow fileDownloadWindow = new FileDownloadWindow(this, desktopManager, session.user());
        windowMenuPresenter.addPermanently(fileDownloadWindow);

        fileDownloadPresenter = new FileDownloadPresenter(session.user(), session.dataCommonsService(), new ConcurrentTaskRunner());
        fileDownloadPresenter.display(fileDownloadWindow);
    }
    
    private void showDatasetsManager() {
        try {
            DatasetsBrowserView datasetsBrowserView = new DatasetsBrowserWindow(session, this, desktopManager);
            DatasetsBrowserPresenter datasetsBrowserPresenter = new DatasetsBrowserPresenter(session);
            datasetsBrowserPresenter.doDisplay(datasetsBrowserView);
        } catch (EmfException e) {
            e.printStackTrace();
        }
    }

    private void setProperties() {
        setSize();
        ToolTipManager.sharedInstance().setDismissDelay(10000);  //set to ten seconds for now...
        super.setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        super.setResizable(true);

        // make main window resizeable and movable on OS X
        if (System.getProperty("os.name").toUpperCase().indexOf("OS X") >= 0) {
            setUndecorated(false);
        }
    }

    protected void windowClosing() {// overriden
        ExitAndLogoutAction exitAction = new ExitAndLogoutAction(EmfConsole.this, desktopManager);
        try {
            exitAction.exit();
        } catch (EmfException e) {
            // NOTE Auto-generated catch block
            e.printStackTrace();
        }
    }

    private void setSize() {
        Dimension dim = new Dimensions().getSize(0.9, 0.9);
        super.setSize(dim);
    }

    private JMenuBar createMenuBar(WindowMenu windowMenu, EmfSession session) {
        JMenuBar menubar = new JMenuBar();

        messagePanel = new SingleLineMessagePanel();

        menubar.add(createFileMenu(session, messagePanel));
        menubar.add(createManageMenu(session, messagePanel));
        menubar.add(windowMenu);
        menubar.add(createToolMenu());
        menubar.add(createHelpMenu());

        menubar.add(messagePanel);

        return menubar;
    }

    private WindowMenu createWindowMenu() {
        WindowMenu windowMenu = new WindowMenu(this);
        addClearAction(windowMenu);

        windowMenuPresenter = new WindowMenuPresenter(windowMenu);
        windowMenu.setWindowMenuViewPresenter(windowMenuPresenter);

        return windowMenu;
    }

    private JMenu createFileMenu(EmfSession session, MessagePanel messagePanel) {
        FileMenu fileMenu = new FileMenu(session, this, messagePanel, desktopManager);
        addClearAction(fileMenu);
        return fileMenu;
    }

    public void doClose() {
        // TODO: auto logout of a session
        presenter.close();
    }

    private JMenu createManageMenu(EmfSession session, MessagePanel messagePanel) {
        manageMenu = new ManageMenu(session, this, messagePanel, desktopManager);
        addClearAction(manageMenu);
        new ManageMenuPresenter(manageMenu, session).observe();
        manageMenu.display();
        return manageMenu;
    }
    
    private JMenu createToolMenu() {
        JMenu menu = new JMenu("Tools");
        menu.setMnemonic(KeyEvent.VK_T);
        addClearAction(menu);


        JMenuItem about = new JMenuItem("Analysis Engine");
        about.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                TableApp.main(new String[0]);
            }
        });
        menu.add(about);

//        JMenuItem sms = new JMenuItem("Sector Scenarios");
//        sms.addActionListener(new ActionListener() {
//            public void actionPerformed(ActionEvent event) {
//            	loadSectorScenarioWindow();
//            }
//        });
//        menu.add(sms);
        return menu; 
    }

    private JMenu createHelpMenu() {
        JMenu menu = new JMenu("Help");
        menu.setMnemonic(KeyEvent.VK_H);
        addClearAction(menu);

        JMenuItem guide = new JMenuItem("User's Guide");
        guide.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                Boolean showDialog = false;
                Desktop desktop = Desktop.isDesktopSupported() ? Desktop.getDesktop() : null;
                if (desktop != null && desktop.isSupported(Desktop.Action.BROWSE)) {
                    try {
                        desktop.browse(new URL(guideUrl).toURI());
                    } catch (Exception e) {
                        showDialog = true;
                    }
                } else {
                    showDialog = true;
                }
                
                if (showDialog) {
                    JOptionPane.showMessageDialog(EmfConsole.this, new Label("", guideMessage), "EMF User's Guide",
                            JOptionPane.INFORMATION_MESSAGE);
                }
            }
        });
        menu.add(guide);

        JMenuItem about = new JMenuItem("About");
        about.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent event) {
                JOptionPane.showMessageDialog(EmfConsole.this, new Label("", aboutMessage), "About the EMF",
                        JOptionPane.INFORMATION_MESSAGE);
            }
        });
        menu.add(about);

        return menu;
    }

    private void loadSectorScenarioWindow() {
        SectorScenarioDialog dialog = new SectorScenarioDialog(EmfConsole.this, session);
        SectorScenarioPresenter presenter = new SectorScenarioPresenter(dialog, session);
        try {
            presenter.display();
        } catch (Exception e) {
            // NOTE Auto-generated catch block
            e.printStackTrace();
        }
    	
    	
//        InputDatasetSelectionView view = new InputDatasetSelectionDialog(parentConsole);
//        InputDatasetSelectionPresenter presenter = new InputDatasetSelectionPresenter(view, session,
//                new DatasetType[] { 
//                    editControlStrategyPresenter.getDatasetType(DatasetType.orlPointInventory),
//                    editControlStrategyPresenter.getDatasetType(DatasetType.orlNonpointInventory),
//                    editControlStrategyPresenter.getDatasetType(DatasetType.orlNonroadInventory),
//                    editControlStrategyPresenter.getDatasetType(DatasetType.orlOnroadInventory)
//                });
//        try {
//            presenter.display(null, false);

    	//
    }
    
    public void observe(EmfConsolePresenter presenter) {
        manageMenu.observe(presenter);
    }
    
    public void display() {
        super.display();
        showDatasetsManager();
    }

    public void displayUserManager() throws EmfException {
        manageMenu.displayUserManager();
    }

    public boolean confirm() {
        emfConfirmDialog = emfConfirmDialog();
        return emfConfirmDialog.confirm();
    }

    private Confirm emfConfirmDialog() {
        String msg = "Some windows have unsaved changes.\nDo you want to continue closing these windows?";
        Confirm emfConfirmDialog = new YesNoDialog(this, "Unsaved Changes Exist", msg);
        return emfConfirmDialog;
    }

    private void addClearAction(final JMenu menu) {
        menu.addMenuListener(new MenuListener() {
            public void menuCanceled(MenuEvent e) {
                clearMesagePanel();
            }

            public void menuDeselected(MenuEvent e) {
                clearMesagePanel();
            }

            public void menuSelected(MenuEvent e) {
                clearMesagePanel();
            }
        });
    }

    public void clearMesagePanel() {
        messagePanel.clear();
    }
    
    public void logExitMessage() {
        try {
            session.userService().logExitMessage(session.user());
        } catch (EmfException e) {
            e.printStackTrace();
        }
    }

    public EmfSession getSession() {     
        return session;
    }

}
