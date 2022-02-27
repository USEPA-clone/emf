package gov.epa.emissions.framework.client.casemanagement.outputs;

import gov.epa.emissions.commons.gui.Button;
import gov.epa.emissions.commons.gui.ComboBox;
import gov.epa.emissions.commons.gui.ConfirmDialog;
import gov.epa.emissions.commons.gui.ManageChangeables;
import gov.epa.emissions.commons.gui.SelectAwareButton;
import gov.epa.emissions.commons.gui.buttons.AddButton;
import gov.epa.emissions.commons.gui.buttons.ExportButton;
import gov.epa.emissions.commons.gui.buttons.RemoveButton;
import gov.epa.emissions.commons.gui.buttons.ViewButton;
import gov.epa.emissions.framework.client.EmfSession;
import gov.epa.emissions.framework.client.SpringLayoutGenerator;
import gov.epa.emissions.framework.client.casemanagement.editor.FindCaseWindow;
import gov.epa.emissions.framework.client.casemanagement.editor.RelatedCaseView;
import gov.epa.emissions.framework.client.casemanagement.inputs.EditInputsTabPresenter;
import gov.epa.emissions.framework.client.console.DesktopManager;
import gov.epa.emissions.framework.client.console.EmfConsole;
import gov.epa.emissions.framework.client.meta.DatasetPropertiesViewer;
import gov.epa.emissions.framework.client.meta.PropertiesViewPresenter;
import gov.epa.emissions.framework.client.swingworker.RefreshSwingWorkerTasks;
import gov.epa.emissions.framework.client.swingworker.SwingWorkerTasks;
import gov.epa.emissions.framework.client.util.ComponentUtility;
import gov.epa.emissions.framework.services.EmfException;
import gov.epa.emissions.framework.services.casemanagement.Case;
import gov.epa.emissions.framework.services.casemanagement.CaseInput;
import gov.epa.emissions.framework.services.casemanagement.jobs.CaseJob;
import gov.epa.emissions.framework.services.casemanagement.outputs.CaseOutput;
import gov.epa.emissions.framework.services.data.EmfDataset;
import gov.epa.emissions.framework.ui.MessagePanel;
import gov.epa.emissions.framework.ui.RefreshObserver;
import gov.epa.emissions.framework.ui.SelectableSortFilterWrapper;
import gov.epa.emissions.framework.ui.YesNoDialog;
import gov.epa.mims.analysisengine.table.sort.SortCriteria;

import java.awt.BorderLayout;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SpringLayout;
import javax.swing.SwingWorker;

public class EditOutputsTab extends JPanel implements EditOutputsTabView, RefreshObserver {

    protected EmfConsole parentConsole;

    private EditOutputsTabPresenter presenter;
    
    protected MessagePanel messagePanel;

    protected OutputsTableData tableData;

    protected ManageChangeables changeables;

    protected SelectableSortFilterWrapper table;

    protected JPanel tablePanel;
    
    protected JPanel layout;
    
    protected Case caseObj;
    
    protected EmfSession session; 
    
    protected ComboBox jobCombo;
    
    protected List<CaseJob> caseJobs; 
    
    protected CaseJob selectedJob=null;
    
    protected DesktopManager desktopManager;



    public EditOutputsTab(EmfConsole parentConsole, ManageChangeables changeables, MessagePanel messagePanel,
            DesktopManager desktopManager, EmfSession session) {
        this(parentConsole, messagePanel, desktopManager, session);
        super.setName("editOutputsTab");       
        this.changeables = changeables;       
    }   
    
    public EditOutputsTab(EmfConsole parentConsole, MessagePanel messagePanel,
            DesktopManager desktopManager, EmfSession session) {
        this.parentConsole = parentConsole;
        this.session=session; 
        this.desktopManager=desktopManager;
        this.messagePanel=messagePanel;
    }
    
    public void doDisplay(EditOutputsTabPresenter presenter, Case caseObj){
        this.presenter = presenter;
        this.caseObj = caseObj;
        new SwingWorkerTasks(this, presenter).execute();
    }
    
    public void display(CaseJob[] jobs) {
        super.setLayout(new BorderLayout());
        super.removeAll();
        CaseOutput[] outputs = new CaseOutput[0];        
        setAllJobs(jobs);    
        super.add(createLayout(outputs), BorderLayout.CENTER);
        super.validate();
    }
    
    

    private void doRefresh(CaseOutput[] outputs){
        messagePanel.clear();
        //selectedJob=(CaseJob) jobCombo.getSelectedItem();
        setupTableModel(outputs);
        table.refresh(tableData);
        panelRefresh();
    }

    private JPanel createLayout(CaseOutput[] outputs){
        layout = new JPanel(new BorderLayout());
        layout.add(createTopPanel(), BorderLayout.NORTH);
        layout.add(tablePanel(outputs, parentConsole), BorderLayout.CENTER);
        layout.add(controlPanel(), BorderLayout.PAGE_END);
        return layout;
    }
    
    public void setAllJobs(CaseJob[] jobs)  {
         
        this.caseJobs = new ArrayList<CaseJob>();
        caseJobs.add(new CaseJob("All"));
        caseJobs.addAll(Arrays.asList(jobs));
    }
    
    private JPanel createTopPanel() {
        JPanel panel = new JPanel(new SpringLayout());
        SpringLayoutGenerator layoutGenerator = new SpringLayoutGenerator();
        jobCombo=new ComboBox("Select One", caseJobs.toArray(new CaseJob[0]));
        jobCombo.setPreferredSize(new Dimension(550,20));
        if (selectedJob!=null)
            jobCombo.setSelectedItem(selectedJob);
            
        jobCombo.addActionListener(filterAction());
        layoutGenerator.addLabelWidgetPair("Job: ", jobCombo, panel);
        layoutGenerator.makeCompactGrid(panel, 1, 2, // rows, cols
                100, 15, // initialX, initialY
                5, 15);// xPad, yPad
        return panel;
    }

    private JPanel tablePanel(CaseOutput[] outputs, EmfConsole parentConsole){
        setupTableModel(outputs);
        changeables.addChangeable(tableData);
        
        tablePanel = new JPanel(new BorderLayout());
        table = new SelectableSortFilterWrapper(parentConsole, tableData, sortCriteria());
        tablePanel.add(table, BorderLayout.CENTER);
        return tablePanel;
    }
    
    private void setupTableModel(CaseOutput[] outputs){
        tableData = new OutputsTableData(outputs, session);
    }

    private SortCriteria sortCriteria() {
        String[] columnNames = { "Sector", "Output name", "Job" };
        return new SortCriteria(columnNames, new boolean[] { true, true, true }, new boolean[] { false, false,
                false });
    }

    private JPanel controlPanel() {
        JPanel container = new JPanel();
        Insets insets = new Insets(1, 2, 1, 2);
        
        String message = "You have asked to open a lot of windows. Do you wish to proceed?";
        ConfirmDialog confirmDialog = new ConfirmDialog(message, "Warning", this);

        Button add = new AddButton(new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                clearMessage();
                doNewOutput(presenter);
            }
        });
        add.setMargin(insets);
        container.add(add);
        
        Button remove = new RemoveButton(new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                messagePanel.clear();
                removeSelectedOutput();
            }
        });
        remove.setMargin(insets);
        container.add(remove);

        SelectAwareButton edit = new SelectAwareButton("Edit", editAction(), table, confirmDialog);
        edit.setMnemonic(KeyEvent.VK_I);
        edit.setMargin(insets);
        container.add(edit);
        
        Button view = new ViewButton("View Datasetz", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                try {
                    displayOutputDatasetsPropertiesViewer();
                } catch (Exception e1) {
                    messagePanel.setError("Could not get dataset for output." 
                            + (e1.getMessage() == null ? "" : e1.getMessage()));
                }
            }
        });
        view.setMnemonic(KeyEvent.VK_Z);
        view.setMargin(insets);
        container.add(view);
        
        Button export = new ExportButton(new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                //
            }
        });
        export.setMnemonic(KeyEvent.VK_E);
        export.setMargin(insets);
        export.setEnabled(false);
        container.add(export);
        
        Button findRelated = new Button("Find", new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                viewCasesReleatedToDataset();
            }
        });
        findRelated.setMnemonic(KeyEvent.VK_N);
        findRelated.setMargin(insets);
        container.add(findRelated);

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(container, BorderLayout.WEST);
        return panel;
    }
 
    private Action editAction() {
        Action action = new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                try {
                    clearMessage();
                    editOutput();
                } catch (EmfException ex) {
                    messagePanel.setError(ex.getMessage());
                }
            }
        };
        return action; 
    }
    
    private void doNewOutput(EditOutputsTabPresenter presenter) {
        NewOutputDialog view = new NewOutputDialog(parentConsole);
        try {
            CaseOutput newOutput = new CaseOutput();
            presenter.addNewOutputDialog(view, newOutput);
        } catch (EmfException e) {
            messagePanel.setError(e.getMessage());
        }
    }
    protected void editOutput() throws EmfException {
        List outputs = table.selected();
        if (outputs.size() == 0) {
            messagePanel.setMessage("Please select output(s) to edit.");
            return;
        }
        for (Iterator iter = outputs.iterator(); iter.hasNext();) {
            CaseOutput output = (CaseOutput) iter.next();
            String title = "Edit Case Output: " + output.getName()+"("+output.getId()+ ")(" + caseObj.getName() + ")";
            EditCaseOutputView outputEditor = new EditCaseOutputWindow(title, desktopManager, parentConsole);
            presenter.editOutput(output, outputEditor);
        }
    }

    private void displayOutputDatasetsPropertiesViewer()  {
        messagePanel.clear();
        final List<EmfDataset> datasets = getSelectedDatasets(table.selected());
        if (datasets.isEmpty()) {
            messagePanel.setMessage("Please select one or more inputs with datasets specified to view.");
            return;
        }
        
        this.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        ComponentUtility.enableComponents(this, false);
        class ViewDatasetPropertiesTask extends SwingWorker<Void, Void> {

            private Container parentContainer;

            public ViewDatasetPropertiesTask(Container parentContainer) {
                this.parentContainer = parentContainer;
            }

            /*
             * Main task. Executed in background thread.
             * don't update gui here
             */
            @Override
            public Void doInBackground() throws EmfException  {
                for (Iterator<EmfDataset> iter = datasets.iterator(); iter.hasNext();) {
                    DatasetPropertiesViewer view = new DatasetPropertiesViewer(session, parentConsole, desktopManager);
                    EmfDataset dataset = iter.next();
                    try {
                        presenter.doDisplayPropertiesView(view, dataset);
                    } catch (EmfException e) {
                        messagePanel.setError(e.getMessage());
                        //e.printStackTrace();
                    } 
                }
                return null;
            }

            /*
             * Executed in event dispatching thread
             */
            @Override
            public void done() {
                try {
                    //make sure something didn't happen
                    get();

                } catch (InterruptedException e1) {
                    //                messagePanel.setError(e1.getMessage());
                    //                setErrorMsg(e1.getMessage());
                } catch (ExecutionException e1) {
                    //                messagePanel.setError(e1.getCause().getMessage());
                    //                setErrorMsg(e1.getCause().getMessage());
                } finally {
                    //                this.parentContainer.setCursor(null); //turn off the wait cursor
                    //                this.parentContainer.
                    ComponentUtility.enableComponents(parentContainer, true);
                    this.parentContainer.setCursor(null); //turn off the wait cursor
                }
            }
        };
        new ViewDatasetPropertiesTask(this).execute();
    }

    
    private List<EmfDataset> getSelectedDatasets(List outputlist) {
        List<EmfDataset> datasetList = new ArrayList<EmfDataset>();

        for (int i=0; i<outputlist.size(); i++) {
            CaseOutput selectedOutput = (CaseOutput) outputlist.get(i);
            if (selectedOutput != null){ 

                int id = selectedOutput.getDatasetId();
                EmfDataset dataset;
                try {
                    dataset = presenter.getDataset(id);
                    if (dataset != null)
                        datasetList.add(dataset);
                } catch (EmfException e) {
                    messagePanel.setError(e.getMessage());
                    e.printStackTrace();
                }
            }
        }

        return datasetList;
    }


    private void removeSelectedOutput(){
        messagePanel.clear();
        CaseOutput[] selected =table.selected().toArray(new CaseOutput[0]);

        if (selected.length==0) {
            messagePanel.setMessage("Please select one or more outputs to remove.");
            return;
        }

        String title = "Warning";
        String message = "Are you sure you want to remove the selected output(s)?";
        int selection = JOptionPane.showConfirmDialog(parentConsole, message, title, JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        String messageDS = "Would you also like to delete the Datasets"+"\n associated with these Outputs?";
        String titleDS = "Discard dataset?";
        if (selection == JOptionPane.YES_OPTION) {
            YesNoDialog dialog = new YesNoDialog(parentConsole, titleDS, messageDS);
            boolean deleteDS=dialog.confirm();
            tableData.remove(selected);
            refresh(tableData.sources());
            
            try {
                presenter.doRemove(selected,deleteDS);
                messagePanel.setMessage(selected.length
                        + " outputs have been removed. Please Refresh to see the revised list of Outputs.");
            }catch (EmfException e){
                messagePanel.setError(e.getMessage());
            }
        }
    }
    public void refresh(CaseOutput[] outputs){
        // note that this will get called when the case is save
            if (outputs != null) {// it's still null if you've never displayed this tab
                doRefresh(outputs);
            }
    }

    public void doRefresh() throws EmfException {
        try {
            refreshJobList(presenter.getCaseJobs());
            new RefreshSwingWorkerTasks(layout, messagePanel, presenter).execute();
        } catch (Exception e) {
            throw new EmfException(e.getMessage());
        }
    }


    public void observe(EditOutputsTabPresenter presenter) {
        this.presenter = presenter;
        this.caseObj=presenter.getCaseObj();
    }
    

    protected void refreshJobList(CaseJob[] jobs) {
        setAllJobs(jobs);
        jobCombo.resetModel(caseJobs.toArray(new CaseJob[0]));
        jobCombo.setSelectedItem(getCaseJob(caseJobs, this.selectedJob));
    }
    
    private CaseJob getCaseJob(List<CaseJob> allJobs, CaseJob selectedJob) {
        if (selectedJob == null)
            return null;
        
        for (Iterator<CaseJob> iter = allJobs.iterator(); iter.hasNext();) {
            CaseJob job = iter.next();
            
            if (selectedJob.getId() == job.getId())
                return job;
        }

        return null;
    }
    
    private AbstractAction filterAction() {
        return new AbstractAction() {
            public void actionPerformed(ActionEvent arg0) {
                selectedJob = (CaseJob) jobCombo.getSelectedItem();
                new RefreshSwingWorkerTasks(layout, messagePanel, presenter).execute();
                //messagePanel.clear();
            }
        };
    } 
    
//    private CaseJob getSelectedJob() {
//        Object selected = jobCombo.getSelectedItem();
//        if (selected == null)
//            return new CaseJob("Select one");
//
//        return (CaseJob) selected;
//    }   

    public void clearMessage() {
        messagePanel.clear();
    }
    
    public void setMessage(String msg) {
        messagePanel.setMessage(msg);
    }

    public void addOutput(CaseOutput addCaseOutput) {
        tableData.add(addCaseOutput);
        setMessage("Added \"" + addCaseOutput.getName() 
                + "\".  Click Refresh to see it in the table.");
    }
    
    private void panelRefresh() {
        tablePanel.removeAll();
        tablePanel.add(table);
        super.validate();
    }
    
    private void viewCasesReleatedToDataset() {
        List outputlist = table.selected();
        if (outputlist == null || outputlist.size() != 1 ){
            messagePanel.setMessage("Please select one output.");
            return; 
        }
        
        int datasetId = ((CaseOutput)outputlist.get(0)).getDatasetId();
        
        if (datasetId == 0 ){
            messagePanel.setMessage("No dataset available. ");
            return; 
        }
        
        try {
            Case[] casesByInputDataset = presenter.getCasesByInputDataset(datasetId);
            Case[] casesByOutputDataset  = presenter.getCasesByOutputDatasets(new int[] {datasetId});
            String datasetName = presenter.getDataset(datasetId).getName();
            String title = "Find Uses of Dataset: " + datasetName;
            RelatedCaseView view = new FindCaseWindow(title, session, parentConsole, desktopManager);
            presenter.doViewRelated(view, casesByOutputDataset, casesByInputDataset);
        } catch (EmfException e) {
            messagePanel.setError(e.getMessage());
        }

    }

    @Override
    public Integer getSelectedJobId() {         
        if ( selectedJob != null) 
            return selectedJob.getId();
        return null;
    } 

    
}
