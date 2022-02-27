package gov.epa.emissions.framework.client.module;

import gov.epa.emissions.commons.gui.Button;
import gov.epa.emissions.commons.gui.ConfirmDialog;
import gov.epa.emissions.commons.gui.ScrollableComponent;
import gov.epa.emissions.commons.gui.SelectAwareButton;
import gov.epa.emissions.commons.gui.TextArea;
import gov.epa.emissions.commons.gui.buttons.CloseButton;
import gov.epa.emissions.commons.util.CustomDateFormat;
import gov.epa.emissions.framework.client.DisposableInteralFrame;
import gov.epa.emissions.framework.client.EmfSession;
import gov.epa.emissions.framework.client.Label;
import gov.epa.emissions.framework.client.SpringLayoutGenerator;
import gov.epa.emissions.framework.client.console.DesktopManager;
import gov.epa.emissions.framework.client.console.EmfConsole;
import gov.epa.emissions.framework.client.meta.DatasetPropertiesViewer;
import gov.epa.emissions.framework.services.EmfException;
import gov.epa.emissions.framework.services.data.EmfDataset;
import gov.epa.emissions.framework.services.module.History;
import gov.epa.emissions.framework.services.module.HistoryDataset;
import gov.epa.emissions.framework.services.module.HistoryInternalDataset;
import gov.epa.emissions.framework.services.module.HistorySubmodule;
import gov.epa.emissions.framework.services.module.Module;
import gov.epa.emissions.framework.ui.RefreshButton;
import gov.epa.emissions.framework.ui.RefreshObserver;
import gov.epa.emissions.framework.ui.SelectableSortFilterWrapper;
import gov.epa.emissions.framework.ui.SingleLineMessagePanel;
import gov.epa.emissions.framework.client.util.ComponentUtility;

import java.awt.BorderLayout;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.Iterator;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SpringLayout;
import javax.swing.SwingWorker;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

public class HistoryDetailsWindow extends DisposableInteralFrame implements HistoryDetailsView, RefreshObserver {
    private HistoryDetailsPresenter presenter;

    private EmfConsole parentConsole;
    private EmfSession session;

    private History history;
    
    private Module module;

    // layout
    private JPanel layout;
    private SingleLineMessagePanel messagePanel;
    private JTabbedPane tabbedPane;

    // summary
    private JPanel summaryPanel;
    private Label  moduleName;
    private Label  runId;
    private Label  startDate;
    private Label  duration;
    private Label  endDate;
    private Label  user;
    private Label  status;
    private Label  result;
    private Label  comment;

    // datasets
    private JPanel                      datasetsPanel;
    private JPanel                      datasetsTablePanel;
    private SelectableSortFilterWrapper datasetsTable;
    private HistoryDatasetsTableData    datasetsTableData;

    // parameters
    private JPanel                      parametersPanel;
    private JPanel                      parametersTablePanel;
    private SelectableSortFilterWrapper parametersTable;
    private HistoryParametersTableData  parametersTableData;

    // scripts
    private JPanel          setupScriptPanel;
    private RSyntaxTextArea setupScript;
    private RTextScrollPane setupScriptScrollPane;

    private JPanel          userScriptPanel;
    private RSyntaxTextArea userScript;
    private RTextScrollPane userScriptScrollPane;

    private JPanel          teardownScriptPanel;
    private RSyntaxTextArea teardownScript;
    private RTextScrollPane teardownScriptScrollPane;

    // submodules
    private JPanel                      submodulesPanel;
    private JPanel                      submodulesTablePanel;
    private SelectableSortFilterWrapper submodulesTable;
    private HistorySubmodulesTableData  submodulesTableData;

    // internal datasets
    private JPanel                           internalDatasetsPanel;
    private JPanel                           internalDatasetsTablePanel;
    private SelectableSortFilterWrapper      internalDatasetsTable;
    private HistoryInternalDatasetsTableData internalDatasetsTableData;

    // internal parameters
    private JPanel                             internalParametersPanel;
    private JPanel                             internalParametersTablePanel;
    private SelectableSortFilterWrapper        internalParametersTable;
    private HistoryInternalParametersTableData internalParametersTableData;

    // logs
    private JPanel   logsPanel;
    private TextArea logs;

    public HistoryDetailsWindow(EmfConsole parentConsole, DesktopManager desktopManager, EmfSession session, History history) {
        super(getWindowTitle(history), new Dimension(800, 600), desktopManager);

        layout = new JPanel();
        layout.setLayout(new BorderLayout());
        this.parentConsole = parentConsole;
        this.session = session;
        super.getContentPane().add(layout);
        
        this.history = history;
        this.module = history.getModule();
    }

    private static String getWindowTitle(History history) {
        return "Module History Details (ID=" + history.getId() + ")";
    }
    
    public void observe(HistoryDetailsPresenter presenter) {
        this.presenter = presenter;
    }

    public void display() {
        layout.removeAll();
        doLayout(layout);
        super.display();
    }

    private void doLayout(JPanel layout) {
        JPanel topPanel = new JPanel(new BorderLayout());
        messagePanel = new SingleLineMessagePanel();
        topPanel.add(messagePanel, BorderLayout.CENTER);
        Button button = new RefreshButton(this, "Refresh Module History Details", messagePanel);
        topPanel.add(button, BorderLayout.EAST);
        
        layout.add(topPanel, BorderLayout.NORTH);
        layout.add(tabbedPane(), BorderLayout.CENTER);
        layout.add(createButtonsPanel(), BorderLayout.SOUTH);
    }


    private JTabbedPane tabbedPane() {
        tabbedPane = new JTabbedPane();
        tabbedPane.addTab("Summary", summaryPanel());
        tabbedPane.addTab("Datasets", datasetsPanel());
        tabbedPane.addTab("Parameters", parametersPanel());
        tabbedPane.addTab("Setup Script", setupScriptPanel());
        if (module.isComposite()) {
            tabbedPane.addTab("Submodules", submodulesPanel());
            tabbedPane.addTab("Internal Datasets", internalDatasetsPanel());
            tabbedPane.addTab("Internal Parameters", internalParametersPanel());
        } else {
            tabbedPane.addTab("User Script", userScriptPanel());
        }
        tabbedPane.addTab("Teardown Script", teardownScriptPanel());
        tabbedPane.addTab("Logs", logsPanel());
        return tabbedPane;
    }

    private JPanel summaryPanel() {
        summaryPanel = new JPanel(new BorderLayout());

        JPanel formPanel = new JPanel(new SpringLayout());
        SpringLayoutGenerator layoutGenerator = new SpringLayoutGenerator();

        moduleName = new Label(module.getName());
        layoutGenerator.addLabelWidgetPair("Module Name:", moduleName, formPanel);

        runId = new Label(history.getRunId() + "");
        layoutGenerator.addLabelWidgetPair("Run ID:", runId, formPanel);

        startDate = new Label(CustomDateFormat.format_MM_DD_YYYY_HH_mm_ss(history.startDate()));
        layoutGenerator.addLabelWidgetPair("Start Date:", startDate, formPanel);

        duration = new Label(history.getDurationSeconds() + " seconds");
        layoutGenerator.addLabelWidgetPair("Duration:", duration, formPanel);

        endDate = new Label(CustomDateFormat.format_MM_DD_YYYY_HH_mm_ss(history.endDate()));
        layoutGenerator.addLabelWidgetPair("End Date:", endDate, formPanel);

        user = new Label(history.getCreator().getName());
        layoutGenerator.addLabelWidgetPair("User:", user, formPanel);

        status = new Label((history.getStatus() == null) ? "" : history.getStatus());
        layoutGenerator.addLabelWidgetPair("Status:", status, formPanel);

        result = new Label((history.getResult() == null) ? "" : history.getResult());
        layoutGenerator.addLabelWidgetPair("Result:", result, formPanel);

        comment = new Label((history.getComment() == null) ? "" : history.getComment());
        layoutGenerator.addLabelWidgetPair("Comment:", comment, formPanel);

        // Lay out the panel.
        layoutGenerator.makeCompactGrid(formPanel, 9, 2, // rows, cols
                10, 10, // initialX, initialY
                10, 10);// xPad, yPad

        summaryPanel.add(formPanel, BorderLayout.PAGE_START);
        return summaryPanel;
    }

    private JPanel datasetsPanel() {
        datasetsTablePanel = new JPanel(new BorderLayout());
        datasetsTableData = new HistoryDatasetsTableData(history.getHistoryDatasets(), session);
        datasetsTable = new SelectableSortFilterWrapper(parentConsole, datasetsTableData, null);
        datasetsTablePanel.add(datasetsTable);

        datasetsPanel = new JPanel(new BorderLayout());
        datasetsPanel.add(datasetsTablePanel, BorderLayout.CENTER);
        datasetsPanel.add(datasetsCrudPanel(), BorderLayout.SOUTH);

        return datasetsPanel;
    }

    private JPanel parametersPanel() {
        parametersTablePanel = new JPanel(new BorderLayout());
        parametersTableData = new HistoryParametersTableData(history.getHistoryParameters());
        parametersTable = new SelectableSortFilterWrapper(parentConsole, parametersTableData, null);
        parametersTablePanel.add(parametersTable);

        parametersPanel = new JPanel(new BorderLayout());
        parametersPanel.add(parametersTablePanel, BorderLayout.CENTER);

        return parametersPanel;
    }

    private JPanel setupScriptPanel() {
        setupScriptPanel = new JPanel(new BorderLayout());
        setupScript = new RSyntaxTextArea(history.getSetupScript());
        setupScript.setEditable(false);
        setupScript.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        setupScript.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_SQL);
        setupScript.setCodeFoldingEnabled(true);
        setupScriptScrollPane = new RTextScrollPane(setupScript);
        setupScriptPanel.add(setupScriptScrollPane);
        return setupScriptPanel;
    }

    private JPanel userScriptPanel() {
        userScriptPanel = new JPanel(new BorderLayout());
        userScript = new RSyntaxTextArea(history.getUserScript());
        userScript.setEditable(false);
        userScript.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        userScript.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_SQL);
        userScript.setCodeFoldingEnabled(true);
        userScriptScrollPane = new RTextScrollPane(userScript);
        userScriptPanel.add(userScriptScrollPane);
        return userScriptPanel;
    }

    private JPanel teardownScriptPanel() {
        teardownScriptPanel = new JPanel(new BorderLayout());
        teardownScript = new RSyntaxTextArea(history.getTeardownScript());
        teardownScript.setEditable(false);
        teardownScript.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        teardownScript.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_SQL);
        teardownScript.setCodeFoldingEnabled(true);
        teardownScriptScrollPane = new RTextScrollPane(teardownScript);
        teardownScriptPanel.add(teardownScriptScrollPane);
        return teardownScriptPanel;
    }

    private JPanel submodulesPanel() {
        submodulesTablePanel = new JPanel(new BorderLayout());
        submodulesTableData = new HistorySubmodulesTableData(history.getHistorySubmodules());
        submodulesTable = new SelectableSortFilterWrapper(parentConsole, submodulesTableData, null);
        submodulesTablePanel.add(submodulesTable);

        submodulesPanel = new JPanel(new BorderLayout());
        submodulesPanel.add(submodulesTablePanel, BorderLayout.CENTER);
        submodulesPanel.add(submodulesCrudPanel(), BorderLayout.SOUTH);

        return submodulesPanel;
    }

    private JPanel internalDatasetsPanel() {
        internalDatasetsTablePanel = new JPanel(new BorderLayout());
        internalDatasetsTableData = new HistoryInternalDatasetsTableData(history.getHistoryInternalDatasets(), session);
        internalDatasetsTable = new SelectableSortFilterWrapper(parentConsole, internalDatasetsTableData, null);
        internalDatasetsTablePanel.add(internalDatasetsTable);

        internalDatasetsPanel = new JPanel(new BorderLayout());
        internalDatasetsPanel.add(internalDatasetsTablePanel, BorderLayout.CENTER);
        internalDatasetsPanel.add(internalDatasetsCrudPanel(), BorderLayout.SOUTH);

        return internalDatasetsPanel;
    }

    private JPanel internalParametersPanel() {
        internalParametersTablePanel = new JPanel(new BorderLayout());
        internalParametersTableData = new HistoryInternalParametersTableData(history.getHistoryInternalParameters());
        internalParametersTable = new SelectableSortFilterWrapper(parentConsole, internalParametersTableData, null);
        internalParametersTablePanel.add(internalParametersTable);

        internalParametersPanel = new JPanel(new BorderLayout());
        internalParametersPanel.add(internalParametersTablePanel, BorderLayout.CENTER);
        // internalParametersPanel.add(internalParametersCrudPanel(), BorderLayout.SOUTH);

        return internalParametersPanel;
    }

    private JPanel logsPanel() {
        logsPanel = new JPanel(new BorderLayout());
        logs = new TextArea("logs", history.getLogMessages(), 60);
        logs.setEditable(false);
        logs.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        ScrollableComponent scrollableLogs = new ScrollableComponent(logs);
        scrollableLogs.setMaximumSize(new Dimension(575, 200));
        logsPanel.add(scrollableLogs);

        return logsPanel;
    }

    private JPanel createButtonsPanel() {
        JPanel panel = new JPanel(new BorderLayout());

        JPanel container = new JPanel();
        FlowLayout layout = new FlowLayout();
        layout.setHgap(20);
        layout.setVgap(25);
        container.setLayout(layout);

        container.add(new CloseButton("Close", closeAction()));

        panel.add(container, BorderLayout.SOUTH);

        return panel;
    }

    @Override
    public void doRefresh() throws EmfException {
        module = presenter.getModule(module.getId());
        history = presenter.getHistory(history.getId());
        refreshSummary();
        refreshDatasets();
        refreshParameters();
        refreshScripts();
        refreshLogs();
    }

    private void refreshSummary() {
        moduleName.setText(module.getName());
        runId.setText(history.getRunId() + "");
        startDate.setText(CustomDateFormat.format_MM_DD_YYYY_HH_mm_ss(history.startDate()));
        duration.setText(history.getDurationSeconds() + " seconds");
        endDate.setText(CustomDateFormat.format_MM_DD_YYYY_HH_mm_ss(history.endDate()));
        user.setText(history.getCreator().getName());
        status.setText((history.getStatus() == null) ? "" : history.getStatus());
        result.setText((history.getResult() == null) ? "" : history.getResult());
        comment.setText((history.getComment() == null) ? "" : history.getComment());
    }
    
    public void refreshDatasets() {
        datasetsTableData = new HistoryDatasetsTableData(history.getHistoryDatasets(), session);
        datasetsTable.refresh(datasetsTableData);
    }

    public void refreshParameters() {
        parametersTableData = new HistoryParametersTableData(history.getHistoryParameters());
        parametersTable.refresh(parametersTableData);
    }

    public void refreshScripts() {
        setupScript.setText(history.getSetupScript());
        if (module.isComposite()) { // TODO handle the case when module changed from composite/simple to simple/composite
            submodulesTableData = new HistorySubmodulesTableData(history.getHistorySubmodules());
            internalDatasetsTableData = new HistoryInternalDatasetsTableData(history.getHistoryInternalDatasets(), session);
            internalParametersTableData = new HistoryInternalParametersTableData(history.getHistoryInternalParameters());
            submodulesTable.refresh(submodulesTableData);
            internalDatasetsTable.refresh(internalDatasetsTableData);
            internalParametersTable.refresh(internalParametersTableData);
        } else {
            userScript.setText(history.getUserScript());
        }
        teardownScript.setText(history.getTeardownScript());
    }

    public void refreshLogs() {
        logs.setText(history.getLogMessages());
    }

    private JPanel datasetsCrudPanel() {
        String message = "You have asked to open a lot of windows. Do you wish to proceed?";
        ConfirmDialog confirmDialog = new ConfirmDialog(message, "Warning", this);

        Action viewAction = new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                viewDatasets();
            }
        };
        SelectAwareButton viewButton = new SelectAwareButton("View Dataset Properties", viewAction, datasetsTable, confirmDialog);
        viewButton.setMnemonic(KeyEvent.VK_D);

        Action viewRelatedModulesAction = new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                viewRelatedModules();
            }
        };
        Button viewRelatedModulesButton = new Button("View Related Modules", viewRelatedModulesAction);
        viewRelatedModulesButton.setMnemonic(KeyEvent.VK_M);

        JPanel crudPanel = new JPanel();
        crudPanel.setLayout(new FlowLayout(FlowLayout.LEFT));
        crudPanel.add(viewButton);
        crudPanel.add(viewRelatedModulesButton);

        return crudPanel;
    }

    private void viewDatasets() {
        clear();
        final List<?> datasets = selectedDatasets();
        if (datasets.isEmpty()) {
            messagePanel.setMessage("Please select one or more Datasets");
            return;
        }
        
        // Long running methods ...
        this.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        ComponentUtility.enableComponents(this, false);

        class ViewDatasetPropertiesTask extends SwingWorker<Void, Void> {
            
            private Container parentContainer;

            public ViewDatasetPropertiesTask(Container parentContainer) {
                this.parentContainer = parentContainer;
            }

            // Main task. Executed in background thread. Don't update GUI here
            @Override
            public Void doInBackground() throws EmfException  {
                for (Iterator iter = datasets.iterator(); iter.hasNext();) {
                    DatasetPropertiesViewer view = new DatasetPropertiesViewer(session, parentConsole, desktopManager);
                    HistoryDataset historyDataset = (HistoryDataset) iter.next();
                    presenter.doDisplayPropertiesView(view, historyDataset.getDatasetId());
                }
                return null;
            }

            // Executed in event dispatching thread
            @Override
            public void done() {
                try {
                    get();
                } catch (InterruptedException e1) {
//                    messagePanel.setError(e1.getMessage());
//                    setErrorMsg(e1.getMessage());
                } catch (ExecutionException e1) {
//                    messagePanel.setError(e1.getCause().getMessage());
//                    setErrorMsg(e1.getCause().getMessage());
                } finally {
                    ComponentUtility.enableComponents(parentContainer, true);
                    this.parentContainer.setCursor(null); //turn off the wait cursor
                }
            }
        };
        new ViewDatasetPropertiesTask(this).execute();
    }

    private void viewRelatedModules() {
        clear();
        @SuppressWarnings("rawtypes")
        final List datasets = selectedDatasets();
        if (datasets.isEmpty()) {
            messagePanel.setMessage("Please select a dataset");
            return;
        } else if (datasets.size() > 1) {
            messagePanel.setMessage("Please select only one dataset");
            return;
        }
        
        HistoryDataset historyDataset = (HistoryDataset) datasets.get(0);
        EmfDataset emfDataset = null;
        if (historyDataset.getDatasetId() != null) {
            try {
                emfDataset = session.dataService().getDataset(historyDataset.getDatasetId());
            } catch (EmfException ex) {
                // ignore exception
            }
        }
        if (emfDataset == null) {
            messagePanel.setMessage("The dataset does not exist");
            return;
        }

        // bring up the window with all related modules
        RelatedModulesWindow view = new RelatedModulesWindow(session, parentConsole, desktopManager, emfDataset);
        presenter.doDisplayRelatedModules(view);
    }

    private List<?> selectedDatasets() {
        return datasetsTable.selected();
    }

    //-------------------------------------------------------------------------
    
    private JPanel submodulesCrudPanel() {
        String message = "You have asked to open a lot of windows. Do you wish to proceed?";
        ConfirmDialog confirmDialog = new ConfirmDialog(message, "Warning", this);

        Action editAction = new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                viewSubmodules();
            }
        };
        SelectAwareButton viewButton = new SelectAwareButton("View", editAction, submodulesTable, confirmDialog);
        
        JPanel crudPanel = new JPanel();
        crudPanel.setLayout(new FlowLayout(FlowLayout.LEFT));
        crudPanel.add(viewButton);

        return crudPanel;
    }

    private void viewSubmodules() {
        List selected = selectedSubmodules();
        if (selected.isEmpty()) {
            messagePanel.setMessage("Please select one or more submodules");
            return;
        }   

        for (Iterator iter = selected.iterator(); iter.hasNext();) {
            HistorySubmodule historySubmodule = (HistorySubmodule) iter.next();
            HistorySubmoduleDetailsWindow view = new HistorySubmoduleDetailsWindow(parentConsole, desktopManager, session, historySubmodule);
            presenter.doDisplayHistorySubmoduleDetails(view);
        }
    }

    private List selectedSubmodules() {
        return submodulesTable.selected();
    }

    //-------------------------------------------------------------------------
    
    private JPanel internalDatasetsCrudPanel() {
        String message = "You have asked to open a lot of windows. Do you wish to proceed?";
        ConfirmDialog confirmDialog = new ConfirmDialog(message, "Warning", this);

        Action viewInternalDatasetsAction = new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                viewInternalDatasets();
            }
        };
        SelectAwareButton viewInternalDatasetsButton = new SelectAwareButton("View Dataset Properties", viewInternalDatasetsAction, internalDatasetsTable, confirmDialog);
        viewInternalDatasetsButton.setMnemonic(KeyEvent.VK_D);

        Action viewInternalRelatedModulesAction = new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                viewInternalRelatedModules();
            }
        };
        Button viewInternalRelatedModulesButton = new Button("View Related Modules", viewInternalRelatedModulesAction);
        viewInternalRelatedModulesButton.setMnemonic(KeyEvent.VK_M);

        JPanel crudPanel = new JPanel();
        crudPanel.setLayout(new FlowLayout(FlowLayout.LEFT));
        crudPanel.add(viewInternalDatasetsButton);
        crudPanel.add(viewInternalRelatedModulesButton);

        return crudPanel;
    }

    private void viewInternalDatasets() {
        clear();
        final List<?> datasets = selectedInternalDatasets();
        if (datasets.isEmpty()) {
            messagePanel.setMessage("Please select one or more internal datasets");
            return;
        }
        
        // Long running methods ...
        this.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        ComponentUtility.enableComponents(this, false);

        class ViewDatasetPropertiesTask extends SwingWorker<Void, Void> {
            
            private Container parentContainer;

            public ViewDatasetPropertiesTask(Container parentContainer) {
                this.parentContainer = parentContainer;
            }

            // Main task. Executed in background thread. Don't update GUI here
            @Override
            public Void doInBackground() throws EmfException  {
                for (Iterator iter = datasets.iterator(); iter.hasNext();) {
                    DatasetPropertiesViewer view = new DatasetPropertiesViewer(session, parentConsole, desktopManager);
                    HistoryInternalDataset historyInternalDataset = (HistoryInternalDataset) iter.next();
                    presenter.doDisplayPropertiesView(view, historyInternalDataset.getDatasetId());
                }
                return null;
            }

            // Executed in event dispatching thread
            @Override
            public void done() {
                try {
                    get();
                } catch (InterruptedException e1) {
                    // ignore
                } catch (ExecutionException e1) {
                    // ignore
                } finally {
                    ComponentUtility.enableComponents(parentContainer, true);
                    this.parentContainer.setCursor(null); // turn off the wait cursor
                }
            }
        };
        new ViewDatasetPropertiesTask(this).execute();
    }

    private void viewInternalRelatedModules() {
        clear();
        @SuppressWarnings("rawtypes")
        final List datasets = selectedInternalDatasets();
        if (datasets.isEmpty()) {
            messagePanel.setMessage("Please select an internal dataset");
            return;
        } else if (datasets.size() > 1) {
            messagePanel.setMessage("Please select only one internal dataset");
            return;
        }
        
        HistoryInternalDataset historyInternalDataset = (HistoryInternalDataset) datasets.get(0);
        EmfDataset emfDataset = historyInternalDataset.getEmfDataset(session.dataService());
        if (emfDataset == null) {
            messagePanel.setMessage("The internal dataset does not exist");
            return;
        }

        // bring up the window with all related modules
        RelatedModulesWindow view = new RelatedModulesWindow(session, parentConsole, desktopManager, emfDataset);
        presenter.doDisplayRelatedModules(view);
    }

    private List<?> selectedInternalDatasets() {
        return internalDatasetsTable.selected();
    }

    //-------------------------------------------------------------------------
    
    private void clear() {
        messagePanel.clear();
    }

    public void windowClosing() {
        doClose();
    }

    private Action closeAction() {
        Action action = new AbstractAction() {
            public void actionPerformed(ActionEvent event) {
                doClose();
            }
        };

        return action;
    }

    private void doClose() {
        presenter.doClose();
    }
}
