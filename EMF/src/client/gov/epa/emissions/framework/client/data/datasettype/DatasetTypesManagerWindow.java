package gov.epa.emissions.framework.client.data.datasettype;

import gov.epa.emissions.commons.data.DatasetType;
import gov.epa.emissions.commons.gui.Button;
import gov.epa.emissions.commons.gui.ComboBox;
import gov.epa.emissions.commons.gui.ConfirmDialog;
import gov.epa.emissions.commons.gui.SelectAwareButton;
import gov.epa.emissions.commons.gui.TextField;
import gov.epa.emissions.commons.gui.buttons.CloseButton;
import gov.epa.emissions.commons.gui.buttons.NewButton;
import gov.epa.emissions.commons.gui.buttons.RemoveButton;
import gov.epa.emissions.framework.client.EmfSession;
import gov.epa.emissions.framework.client.DisposableInteralFrame;
import gov.epa.emissions.framework.client.console.DesktopManager;
import gov.epa.emissions.framework.client.console.EmfConsole;
import gov.epa.emissions.framework.client.util.ComponentUtility;
import gov.epa.emissions.framework.services.EmfException;
import gov.epa.emissions.framework.services.basic.BasicSearchFilter;
import gov.epa.emissions.framework.services.data.DatasetTypeFilter;
import gov.epa.emissions.framework.services.data.EmfDataset;
import gov.epa.emissions.framework.ui.MessagePanel;
import gov.epa.emissions.framework.ui.RefreshButton;
import gov.epa.emissions.framework.ui.RefreshObserver;
import gov.epa.emissions.framework.ui.SelectableSortFilterWrapper;
import gov.epa.emissions.framework.ui.SingleLineMessagePanel;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;

import javax.swing.*;

public class DatasetTypesManagerWindow extends DisposableInteralFrame implements DatasetTypesManagerView, RefreshObserver {

    private DatasetTypesManagerPresenter presenter;

    //private SortFilterSelectModel selectModel;

    private SelectableSortFilterWrapper table;

    private JPanel layout;
    
    private JPanel tablePanel;

    private MessagePanel messagePanel;

    private EmfConsole parentConsole;

    private EmfSession session;
    
    private Button editButton, newButton, removeButton;

    private TextField simpleTextFilter;
    private ComboBox filterFieldsComboBox;

    public DatasetTypesManagerWindow(EmfSession session, EmfConsole parentConsole, DesktopManager desktopManager) {
        super("Dataset Type Manager", new Dimension(700, 350), desktopManager);
        super.setName("datasetTypeManager");
        
        this.session = session;
        this.parentConsole = parentConsole;
        
        layout = new JPanel();
        this.getContentPane().add(layout);
    }

    public void observe(DatasetTypesManagerPresenter presenter) {
        this.presenter = presenter;
    }

    public void refresh(DatasetType[] types) {
        //doLayout(types);
        table.refresh(new DatasetTypesTableData(types));
        panelRefresh();
    }
    
    private void panelRefresh() {
        tablePanel.removeAll();
        tablePanel.add(table);
        super.refreshLayout();
    }

    public void display() {
        createLayout();
        super.display();
        populate();
    }

    private void createLayout() {
        layout.removeAll();
        layout.setLayout(new BorderLayout());

        layout.add(createTopPanel(), BorderLayout.NORTH);
        layout.add(tablePanel(), BorderLayout.CENTER);
        layout.add(createControlPanel(), BorderLayout.SOUTH);
    }

    private JPanel tablePanel() {
        tablePanel = new JPanel(new BorderLayout());
        table = new SelectableSortFilterWrapper(parentConsole, new DatasetTypesTableData(new DatasetType[] {}), null);
        table.getTable().getAccessibleContext().setAccessibleName("List of dataset types");
        tablePanel.add(table);
        return tablePanel;
    }

    private JPanel createTopPanel() {
        JPanel panel = new JPanel(new BorderLayout());

        messagePanel = new SingleLineMessagePanel();
        panel.add(messagePanel, BorderLayout.CENTER);

        Button button = new RefreshButton(this, "Refresh Dataset Types", messagePanel);
        panel.add(button, BorderLayout.EAST);


        JPanel topPanel = new JPanel(new BorderLayout());
        simpleTextFilter = new TextField("textfilter", 25);
        simpleTextFilter.setPreferredSize(new Dimension(360, 25));
        simpleTextFilter.setEditable(true);
        simpleTextFilter.addActionListener(simpleFilterTypeAction());


        JPanel advPanel = new JPanel(new BorderLayout(5, 2));

        //get table column names
//        String[] columns = new String[] {"Module Name", "Composite?", "Final?", "Tags", "Project", "Module Type", "Version", "Creator", "Date", "Lock Owner", "Lock Date", "Description" };//(new ModulesTableData(new ConcurrentSkipListMap<Integer, LiteModule>())).columns();

        filterFieldsComboBox = new ComboBox("Select one", (new DatasetTypeFilter()).getFilterFieldNames(), "Fields Filter Text");
        filterFieldsComboBox.setSelectedIndex(1);
        filterFieldsComboBox.setPreferredSize(new Dimension(180, 25));
        filterFieldsComboBox.addActionListener(simpleFilterTypeAction());

        advPanel.add(getDilterFieldsComboBoxPanel("Filter Fields:", filterFieldsComboBox), BorderLayout.LINE_START);
        advPanel.add(simpleTextFilter, BorderLayout.EAST);
//        advPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 5));

        topPanel.add(advPanel, BorderLayout.EAST);

        JPanel mainPanel = new JPanel(new GridLayout(2, 1));
        mainPanel.add(panel);
        mainPanel.add(topPanel);

        return mainPanel;
    }

    private Action simpleFilterTypeAction() {
        return new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
//                DatasetType type = getSelectedDSType();
//                try {
//                    // count the number of datasets and do refresh
//                    if (dsTypesBox.getSelectedIndex() > 0)
                try {
                    doRefresh();
                } catch (EmfException e1) {
                    e1.printStackTrace();
                }
//                } catch (EmfException e1) {
////                    messagePanel.setError("Could not retrieve all modules " /*+ type.getName()*/);
//                }
            }
        };
    }

    private JPanel getDilterFieldsComboBoxPanel(String label, JComboBox box) {
        JPanel panel = new JPanel(new BorderLayout(5, 2));
        JLabel jlabel = new JLabel(label);
        jlabel.setHorizontalAlignment(JLabel.RIGHT);
        jlabel.setLabelFor(box);
        panel.add(jlabel, BorderLayout.WEST);
        panel.add(box, BorderLayout.CENTER);
        panel.setBorder(BorderFactory.createEmptyBorder(5, 20, 5, 10));

        return panel;
    }

    private JPanel createControlPanel() {
        JPanel crudPanel = createCrudPanel();

        JPanel closePanel = new JPanel();
        Button closeButton = new CloseButton(new AbstractAction() {
            public void actionPerformed(ActionEvent event) {
                presenter.doClose();
            }
        });
        closePanel.add(closeButton);
        getRootPane().setDefaultButton(closeButton);

        JPanel controlPanel = new JPanel();
        controlPanel.setLayout(new BorderLayout());

        controlPanel.add(crudPanel, BorderLayout.WEST);
        controlPanel.add(closePanel, BorderLayout.EAST);

        return controlPanel;
    }

    private JPanel createCrudPanel() {
        String message = "You have asked to open a lot of windows. Do you wish to proceed?";
        ConfirmDialog confirmDialog = new ConfirmDialog(message, "Warning", this);

        Action viewAction = new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                viewDatasetTypes();
            }
        };
        SelectAwareButton viewButton = new SelectAwareButton("View", viewAction, table, confirmDialog);

        Action editAction = new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                editDatasetTypes();
            }
        };
        editButton = new SelectAwareButton("Edit", editAction, table, confirmDialog);

        Action createAction = new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                createDatasetType();
            }
        };
        newButton = new NewButton(createAction);
        
        Action removeAction = new AbstractAction() {
            public void actionPerformed(ActionEvent e) {
                removeDatasetType();
            }
        };
        removeButton = new RemoveButton(removeAction);
        JPanel crudPanel = new JPanel();
        crudPanel.setLayout(new FlowLayout());
        crudPanel.add(viewButton);
        crudPanel.add(editButton);
        crudPanel.add(newButton);
        crudPanel.add(removeButton);
        if (!session.user().isAdmin()){
            editButton.setEnabled(false);
            newButton.setEnabled(false);
            removeButton.setEnabled(false);
        }

        return crudPanel;
    }

    private void viewDatasetTypes() {
        List selected = selected();
        for (Iterator iter = selected.iterator(); iter.hasNext();) {
            DatasetType type = (DatasetType) iter.next();
            try {
                presenter.doView(type, viewableView());
            } catch (EmfException e) {
                messagePanel.setError("Could not display: " + type.getName() + "." + e.getMessage());
                break;
            }
        }
    }

    private void editDatasetTypes() {
        List selected = selected();
        if (selected.isEmpty()) {
            messagePanel.setMessage("Please select one or more dataset types");
            return;
        }   
        
        for (Iterator iter = selected.iterator(); iter.hasNext();) {
            DatasetType type = (DatasetType) iter.next();
            try {
                presenter.doEdit(type, editableView(), viewableView());
            } catch (EmfException e) {
                messagePanel.setError("Could not display: " + type.getName() + "." + e.getMessage());
                break;
            }
        }
    }

    private void createDatasetType() {
        NewDatasetTypeWindow view = new NewDatasetTypeWindow(parentConsole, desktopManager, session);
        presenter.displayNewDatasetTypeView(view);
    }
    
    private void removeDatasetType() {
        messagePanel.clear();
        List<?> selected = selected();
        if (selected.isEmpty()) {
            messagePanel.setMessage("Please select one or more dataset types");
            return;
        }   

        String message = "Are you sure you want to remove the selected " + selected.size() + " dataset type(s)?";
        int selection = JOptionPane.showConfirmDialog(parentConsole, message, "Warning", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (selection == JOptionPane.YES_OPTION) {
            try {
                presenter.doRemove(selected.toArray(new DatasetType[0]));
                messagePanel.setMessage(selected.size()
                        + " dataset types have been removed. Please Refresh to see the revised list of types.");
            } catch (EmfException e) {
              JOptionPane.showConfirmDialog(parentConsole, e.getMessage(), "Error", JOptionPane.CLOSED_OPTION, JOptionPane.ERROR_MESSAGE);
            }
        }
    }


    private List selected() {
        return table.selected();
    }

    private ViewableDatasetTypeWindow viewableView() {
        ViewableDatasetTypeWindow view = new ViewableDatasetTypeWindow(desktopManager);
        return view;
    }

    private EditableDatasetTypeView editableView() {
        EditableDatasetTypeWindow view = new EditableDatasetTypeWindow(session,parentConsole, desktopManager);
        return view;
    }

    public EmfConsole getParentConsole() {
        return this.parentConsole;
    }

    public void doRefresh() throws EmfException {
        populate();
    }

    private BasicSearchFilter getBasicSearchFilter() {

        BasicSearchFilter searchFilter = new BasicSearchFilter();
        String fieldName = (String)filterFieldsComboBox.getSelectedItem();
        if (fieldName != null) {
            searchFilter.setFieldName(fieldName);
            searchFilter.setFieldValue(simpleTextFilter.getText());
        }
        return searchFilter;
    }

    @Override
    public void populate() {
        //long running methods.....
        this.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        ComponentUtility.enableComponents(this, false);

        //Instances of javax.swing.SwingWorker are not reusable, so
        //we create new instances as needed.
        class GetDatasetTypesTask extends SwingWorker<DatasetType[], Void> {
            
            private Container parentContainer;

            public GetDatasetTypesTask(Container parentContainer) {
                this.parentContainer = parentContainer;
            }

            /*
             * Main task. Executed in background thread.
             * don't update gui here
             */
            @Override
            public DatasetType[] doInBackground() throws EmfException  {
                return presenter.getDatasetTypes(getBasicSearchFilter());
            }

            /*
             * Executed in event dispatching thread
             */
            @Override
            public void done() {
                try {
                    //make sure something didn't happen
                    refresh(get());
                } catch (InterruptedException e1) {
//                    messagePanel.setError(e1.getMessage());
//                    setErrorMsg(e1.getMessage());
                } catch (ExecutionException e1) {
//                    messagePanel.setError(e1.getCause().getMessage());
//                    setErrorMsg(e1.getCause().getMessage());
                } finally {
//                    this.parentContainer.setCursor(null); //turn off the wait cursor
//                    this.parentContainer.
                    ComponentUtility.enableComponents(parentContainer, true);
                    if (!session.user().isAdmin()){
                        editButton.setEnabled(false);
                        newButton.setEnabled(false);
                        removeButton.setEnabled(false);
                    }
                    this.parentContainer.setCursor(null); //turn off the wait cursor
                    filterFieldsComboBox.grabFocus();
                }
            }
        };
        new GetDatasetTypesTask(this).execute();
    }
}
