package gov.epa.emissions.framework.client.tempalloc.editor;

import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.List;

import gov.epa.emissions.commons.data.Dataset;
import gov.epa.emissions.commons.data.DatasetType;
import gov.epa.emissions.commons.data.InternalSource;
import gov.epa.emissions.commons.db.version.Version;
import gov.epa.emissions.commons.gui.BorderlessButton;
import gov.epa.emissions.commons.gui.Button;
import gov.epa.emissions.commons.gui.ManageChangeables;
import gov.epa.emissions.commons.gui.TextArea;
import gov.epa.emissions.framework.client.EmfSession;
import gov.epa.emissions.framework.client.SpringLayoutGenerator;
import gov.epa.emissions.framework.client.console.DesktopManager;
import gov.epa.emissions.framework.client.console.EmfConsole;
import gov.epa.emissions.framework.client.data.dataset.InputDatasetSelectionDialog;
import gov.epa.emissions.framework.client.data.dataset.InputDatasetSelectionPresenter;
import gov.epa.emissions.framework.client.data.dataset.InputDatasetSelectionView;
import gov.epa.emissions.framework.client.data.viewer.DataViewPresenter;
import gov.epa.emissions.framework.client.data.viewer.DataViewer;
import gov.epa.emissions.framework.client.meta.DatasetPropertiesViewer;
import gov.epa.emissions.framework.client.meta.PropertiesViewPresenter;
import gov.epa.emissions.framework.services.EmfException;
import gov.epa.emissions.framework.services.data.EmfDataset;
import gov.epa.emissions.framework.services.tempalloc.TemporalAllocation;
import gov.epa.emissions.framework.services.tempalloc.TemporalAllocationInputDataset;
import gov.epa.emissions.framework.ui.Border;
import gov.epa.emissions.framework.ui.MessagePanel;
import gov.epa.emissions.framework.ui.SelectableSortFilterWrapper;
import gov.epa.emissions.framework.ui.SingleLineMessagePanel;

import javax.swing.AbstractAction;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SpringLayout;

public class TemporalAllocationInventoriesTab extends JPanel implements TemporalAllocationTabView {
    
    private TemporalAllocation temporalAllocation;

    private ManageChangeables changeablesList;

    private EmfSession session;

    private MessagePanel messagePanel;
    
    private EmfConsole parentConsole;
    
    private DesktopManager desktopManager;
    
    private TemporalAllocationPresenter presenter;
    
    private TemporalAllocationInventoriesTableData tableData;

    private SelectableSortFilterWrapper table;

    private JPanel tablePanel;
    
    private TextArea filter;
    
    public TemporalAllocationInventoriesTab(TemporalAllocation temporalAllocation, EmfSession session, 
            ManageChangeables changeablesList, SingleLineMessagePanel messagePanel, 
            EmfConsole parentConsole, DesktopManager desktopManager,
            TemporalAllocationPresenter presenter) {
        super.setName("inventories");
        this.temporalAllocation = temporalAllocation;
        tableData = new TemporalAllocationInventoriesTableData(temporalAllocation.getTemporalAllocationInputDatasets(), session);
        this.session = session;
        this.changeablesList = changeablesList;
        this.messagePanel = messagePanel;
        this.parentConsole = parentConsole;
        this.desktopManager = desktopManager;
        this.presenter = presenter;
    }
    
    public void setTemporalAllocation(TemporalAllocation temporalAllocation) {
        this.temporalAllocation = temporalAllocation;
    }
    
    public void display() {
        super.setLayout(new BorderLayout());
        super.add(buildSortFilterPanel(), BorderLayout.CENTER);
        super.add(buildInvFilterPanel(), BorderLayout.SOUTH);
    }

    private JPanel buildSortFilterPanel() {
        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new Border("Inventories to Process"));
        panel.add(tablePanel(), BorderLayout.CENTER);
        panel.add(buttonPanel(), BorderLayout.SOUTH);
        return panel; 
    }
    
    private JPanel tablePanel() {
        tablePanel = new JPanel(new BorderLayout());
        table = new SelectableSortFilterWrapper(parentConsole, tableData, null);
        tablePanel.add(table);

        return tablePanel;
    }

    private JPanel buttonPanel() {
        JPanel panel = new JPanel();

        Button addButton = new BorderlessButton("Add", new AbstractAction() {
            public void actionPerformed(ActionEvent event) {
                try {
                    addAction();
                } catch (EmfException e) {
                    // NOTE Auto-generated catch block
                    e.printStackTrace();
                }
            }
        });
        addButton.setMnemonic(KeyEvent.VK_A);
        addButton.setEnabled(presenter.isEditing());
        panel.add(addButton);

        Button editButton = new BorderlessButton("Set Version", new AbstractAction() {
            public void actionPerformed(ActionEvent event) {
                setVersionAction();
            }
        });
        editButton.setMnemonic(KeyEvent.VK_E);
        editButton.setEnabled(presenter.isEditing());
        panel.add(editButton);

        Button removeButton = new BorderlessButton("Remove", new AbstractAction() {
            public void actionPerformed(ActionEvent event) {           
                    removeAction();
            }
        });
        removeButton.setMnemonic(KeyEvent.VK_O);
        removeButton.setEnabled(presenter.isEditing());
        panel.add(removeButton);

        Button viewButton = new BorderlessButton("View Properties", new AbstractAction() {
            public void actionPerformed(ActionEvent event) {
                try {
                    viewAction();
                } catch (EmfException e) {
                    messagePanel.setError("Error viewing dataset: " + e.getMessage());
                }
            }
        });
        viewButton.setMnemonic(KeyEvent.VK_V);
        panel.add(viewButton);

        Button viewDataButton = new BorderlessButton("View Data", new AbstractAction() {
            public void actionPerformed(ActionEvent event) {
                try {
                    viewDataAction();
                } catch (EmfException e) {
                    messagePanel.setError("Error viewing dataset data: " + e.getMessage());
                }
            }
        });
        viewDataButton.setMnemonic(KeyEvent.VK_D);
        panel.add(viewDataButton);

        return panel;
    }
    
    private JPanel buildInvFilterPanel() {
        JPanel panel = new JPanel(new SpringLayout());
        panel.setBorder(new Border("Filters"));

        String value = temporalAllocation.getFilter();
        if (value == null) value = "";
        
        filter = new TextArea("filter", value, 40, 2);
        filter.setToolTipText("Enter a filter that could be entered as a SQL where clause (e.g., ANN_VALUE>5000 and SCC like '30300%')");
        JScrollPane scrollPane = new JScrollPane(filter);
        changeablesList.addChangeable(filter);
        filter.setEditable(presenter.isEditing());
        
        SpringLayoutGenerator layoutGenerator = new SpringLayoutGenerator();
        layoutGenerator.addLabelWidgetPair("Inventory Filter:", scrollPane, panel);
        
        layoutGenerator.makeCompactGrid(panel, 1, 2, // rows, cols
                5, 5, // initialX, initialY
                10, 5);// xPad, yPad

        return panel; 
    }

    private void addAction() throws EmfException {
        InputDatasetSelectionView view = new InputDatasetSelectionDialog(parentConsole);
        InputDatasetSelectionPresenter presenter = new InputDatasetSelectionPresenter(view, session,
                new DatasetType[] { 
                    session.getLightDatasetType(DatasetType.orlPointInventory),
                    session.getLightDatasetType(DatasetType.orlNonpointInventory),
                    session.getLightDatasetType(DatasetType.orlNonroadInventory),
                    session.getLightDatasetType(DatasetType.orlOnroadInventory),
                    session.getLightDatasetType(DatasetType.FLAT_FILE_2010_POINT),
                    session.getLightDatasetType(DatasetType.FLAT_FILE_2010_NONPOINT),
                    session.getLightDatasetType(DatasetType.FLAT_FILE_2010_POINT_DAILY),
                    session.getLightDatasetType(DatasetType.FLAT_FILE_2010_NONPOINT_DAILY)
                });
        try {
            presenter.display(null, false);
            if (view.shouldCreate()){
                EmfDataset[] inputDatasets = presenter.getDatasets();
                TemporalAllocationInputDataset[] temporalAllocationInputDatasets = new TemporalAllocationInputDataset[inputDatasets.length];
                for (int i = 0; i < inputDatasets.length; i++) {
                    temporalAllocationInputDatasets[i] = new TemporalAllocationInputDataset(inputDatasets[i]);
                    temporalAllocationInputDatasets[i].setVersion(inputDatasets[i].getDefaultVersion());
                }
                tableData.add(temporalAllocationInputDatasets);
                //if (inputDatasets.length > 0) editPresenter.fireTracking();
                refresh();
            }
        } catch (Exception exp) {
            messagePanel.setError(exp.getMessage());
        }
    }

    private void setVersionAction(){
        messagePanel.clear();
        //get a single selected item
        List selected = table.selected();
        if (selected.size() != 1) {
            messagePanel.setMessage("Please select only a single inventory to set its version.");
            return;
        }

        TemporalAllocationInputDataset inputDataset = (TemporalAllocationInputDataset)selected.get(0);
        EmfDataset dataset = inputDataset.getInputDataset();

        //Show select version dialog
        TAInventoryEditDialog dialog = new TAInventoryEditDialog(parentConsole, dataset, presenter, this);
        dialog.run();
    }

    private void viewAction() throws EmfException {
        messagePanel.clear();
        List selected = table.selected();

        if (selected.size() == 0) {
            messagePanel.setError("Please select an item to view.");
            return;
        }

        for (int i = 0; i < selected.size(); i++) {
            TemporalAllocationInputDataset inputDataset = (TemporalAllocationInputDataset)selected.get(i);
            PropertiesViewPresenter presenter = new PropertiesViewPresenter(inputDataset.getInputDataset(), session);
            DatasetPropertiesViewer view = new DatasetPropertiesViewer(session, parentConsole, desktopManager);
            presenter.doDisplay(view);
        }
    }

    private void viewDataAction() throws EmfException {
        messagePanel.clear();
        List selected = table.selected();

        if (selected.size() == 0) {
            messagePanel.setError("Please select an item to view.");
            return;
        }

        for (int i = 0; i < selected.size(); i++) {
            TemporalAllocationInputDataset inputDataset = (TemporalAllocationInputDataset)selected.get(i);
            showDatasetDataViewer(inputDataset.getInputDataset());
        }
    }
    
    private void showDatasetDataViewer(EmfDataset dataset) {
        try {
            Version[] versions = presenter.getVersions(dataset);
            //if just one version, then go directly to the dataviewer
            if (versions.length == 1) {
                DataViewer dataViewerView = new DataViewer(dataset, parentConsole, desktopManager);
                DataViewPresenter dataViewPresenter = new DataViewPresenter(dataset, versions[0], presenter.getTableName(dataset), dataViewerView, session);
                dataViewPresenter.display();
            //else goto to dataset editior and display different version to display
            } else {
                DatasetPropertiesViewer datasetPropertiesViewerView = new DatasetPropertiesViewer(session, parentConsole, desktopManager);
                presenter.doDisplayPropertiesView(datasetPropertiesViewerView, dataset);
                datasetPropertiesViewerView.setDefaultTab(1);
            }
//            presenter.doView(version, table, view);
        } catch (EmfException e) {
//            displayError(e.getMessage());
        }
    }
 
    protected void removeAction() {
        messagePanel.clear();
        List selected = table.selected();

        if (selected.size() == 0) {
            messagePanel.setError("Please select an inventory to remove.");
            return;
        }

        TemporalAllocationInputDataset[] inputDatasets = (TemporalAllocationInputDataset[])selected.toArray(new TemporalAllocationInputDataset[0]);

        if (inputDatasets.length == 0)
            return;

        String title = "Warning";
        String message = "Are you sure you want to remove the selected inventories?";
        int selection = JOptionPane.showConfirmDialog(parentConsole, message, title, JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (selection == JOptionPane.YES_OPTION) {
            tableData.remove(inputDatasets);
            //if (inputDatasets.length > 0) editPresenter.fireTracking();
            refresh();
        }
    }
    
    public void save() {
        TemporalAllocationInputDataset[] inputDatasets = {};
        if (tableData != null) {
            inputDatasets = new TemporalAllocationInputDataset[tableData.rows().size()];
            for (int i = 0; i < tableData.rows().size(); i++) {
                inputDatasets[i] = (TemporalAllocationInputDataset)tableData.element(i);
            }
            temporalAllocation.setTemporalAllocationInputDatasets(inputDatasets);
        }
        
        String value = filter.getText().trim();
        temporalAllocation.setFilter(value);
    }
    
    private void refresh(){
        table.refresh(tableData);
        panelRefresh();
    }
    
    private void panelRefresh() {
        tablePanel.removeAll();
        tablePanel.add(table);
        super.validate();
    }
    
    public void editVersion(Version version, EmfDataset dataset) {
        messagePanel.clear();
        //get all measures
        TemporalAllocationInputDataset[] datasets = tableData.sources();
        //get versions of selected item
        if (version != null) {
            //validate value
            
            //only update items that have been selected          
            for (int j = 0; j < datasets.length; j++) {
                if (dataset.equals(datasets[j].getInputDataset())) {
                    datasets[j].setVersion(version.getVersion());
                }
            }
            //repopulate the table data
            tableData = new TemporalAllocationInventoriesTableData(datasets, session);
            
            refresh();
        }
    }
    
    public void prepareRun() throws EmfException {
        if (tableData == null ||
            tableData.rows().size() == 0) {
            throw new EmfException("Please add at least one inventory to the temporal allocation.");
        }
        if (temporalAllocation.getFilter().length() == 0) {
            String title = "Warning";
            String message = "You haven't entered an inventory filter.\n"
                    + "Are you sure you want to process all the sources in the inventor";
            if (tableData.rows().size() > 1) {
                message += "ies?";
            } else {
                message += "y?";
            }
            int selection = JOptionPane.showConfirmDialog(parentConsole, message, title, JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE);
            if (selection == JOptionPane.NO_OPTION) {
                throw new EmfException("Please enter an inventory filter.");
            }
        }
    }
}
