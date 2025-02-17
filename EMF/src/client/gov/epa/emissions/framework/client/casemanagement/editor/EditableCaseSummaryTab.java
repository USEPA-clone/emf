package gov.epa.emissions.framework.client.casemanagement.editor;

import gov.epa.emissions.commons.data.Region;
import gov.epa.emissions.commons.data.Sector;
import gov.epa.emissions.commons.db.version.Version;
import gov.epa.emissions.commons.gui.CheckBox;
import gov.epa.emissions.commons.gui.ComboBox;
import gov.epa.emissions.commons.gui.EditableComboBox;
import gov.epa.emissions.commons.gui.ManageChangeables;
import gov.epa.emissions.commons.gui.ScrollableComponent;
import gov.epa.emissions.commons.gui.TextArea;
import gov.epa.emissions.commons.gui.TextField;
import gov.epa.emissions.commons.util.CustomDateFormat;
import gov.epa.emissions.framework.client.EmfSession;
import gov.epa.emissions.framework.client.Label;
import gov.epa.emissions.framework.client.SpringLayoutGenerator;
import gov.epa.emissions.framework.client.console.DesktopManager;
import gov.epa.emissions.framework.client.console.EmfConsole;
import gov.epa.emissions.framework.client.data.AddRemoveRegionsWidget;
import gov.epa.emissions.framework.client.swingworker.RefreshSwingWorkerTasks;
import gov.epa.emissions.framework.services.EmfException;
import gov.epa.emissions.framework.services.casemanagement.Abbreviation;
import gov.epa.emissions.framework.services.casemanagement.Case;
import gov.epa.emissions.framework.services.casemanagement.ModelToRun;
import gov.epa.emissions.framework.services.casemanagement.RunStatuses;
import gov.epa.emissions.framework.services.cost.controlmeasure.YearValidation;
import gov.epa.emissions.framework.services.data.GeoRegion;
import gov.epa.emissions.framework.ui.MessagePanel;
import gov.epa.emissions.framework.ui.NumberFieldVerifier;
import gov.epa.emissions.framework.ui.RefreshObserver;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SpringLayout;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

public class EditableCaseSummaryTab extends JPanel implements EditableCaseSummaryTabView, RefreshObserver {

    protected Case caseObj;

    private ManageChangeables changeablesList;

    protected TextField name;

    protected TextField futureYear;

    protected TextField template;

    protected TextField numMetLayers, numEmissionLayers;

    protected TextArea description;

    protected EditableComboBox projectsCombo;

    protected EmfSession session;

    protected ComboBox modelToRunCombo;

    protected TextField modelVersionField;

    protected ComboBox modRegionsCombo;

    protected EditableComboBox abbreviationsCombo;

    protected EditableComboBox airQualityModelsCombo;

    protected ComboBox categoriesCombo;

    protected EditableComboBox emissionsYearCombo;

    protected EditableComboBox meteorlogicalYearCombo;

    protected EditableComboBox speciationCombo;

    protected CheckBox isFinal;

    protected CheckBox isTemplate;

    protected AddRemoveSectorWidget sectorsWidget;
    
    protected AddRemoveRegionsWidget regionsWidget;

    protected ComboBox runStatusCombo;

    protected TextField startDate;

    protected TextField endDate;

    protected Dimension defaultDimension = new Dimension(255, 22);

    protected EditableCaseSummaryTabPresenter presenter;
    
    protected EmfConsole parentConsole;
    
    protected DesktopManager desktopManager;

    protected MessagePanel messagePanel;

    protected int fieldWidth = 23;
    
    protected JPanel layout;

    public EditableCaseSummaryTab(Case caseObj, EmfSession session, ManageChangeables changeablesList,
            MessagePanel messagePanel, EmfConsole parentConsole) {
        this(messagePanel, parentConsole);
        super.setName("summary");   
        this.changeablesList = changeablesList; 
        this.session = session;  
        this.caseObj = caseObj;
    }
    
    public EditableCaseSummaryTab(MessagePanel messagePanel, EmfConsole parentConsole) { 
        this.parentConsole = parentConsole;
        this.messagePanel = messagePanel;
    }

    public void display() throws EmfException {
        setLayout();
        addChangables();
    }

    private void setLayout() throws EmfException {
        super.setLayout(new BorderLayout());

        layout = new JPanel();
        layout.setLayout(new BoxLayout(layout, BoxLayout.Y_AXIS));
        layout.add(createOverviewSection());
        layout.add(createLowerSection());

        super.add(layout, BorderLayout.CENTER);
    }

    private JPanel createOverviewSection() throws EmfException {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.GRAY));

        JPanel container = new JPanel();
        container.setLayout(new GridLayout(1, 2));
        container.add(createLeftOverviewSection());
        container.add(createRightOverviewSection());

        panel.add(container, BorderLayout.CENTER);

        return panel;
    }

    protected JPanel createLeftOverviewSection() throws EmfException {
        JPanel panel = new JPanel(new SpringLayout());
        SpringLayoutGenerator layoutGenerator = new SpringLayoutGenerator();

        layoutGenerator.addLabelWidgetPair("Name:", name(), panel);
        layoutGenerator.addLabelWidgetPair("Category:", categories(), panel);
        // adding extra spaces in the label shifts things over a bit to align upper and lower panels
        layoutGenerator.addLabelWidgetPair("Description:             ", description(), panel);
        layoutGenerator.addLabelWidgetPair("Project:", projects(), panel);
        layoutGenerator.addLabelWidgetPair("Run Status:", runStatus(), panel);

        // Lay out the panel.
        layoutGenerator.makeCompactGrid(panel, 5, 2, // rows, cols
                10, 10, // initialX, initialY
                5, 10);// xPad, yPad

        return panel;
    }

    protected JPanel createRightOverviewSection() throws EmfException {
        JPanel panel = new JPanel(new SpringLayout());
        SpringLayoutGenerator layoutGenerator = new SpringLayoutGenerator();

        layoutGenerator.addLabelWidgetPair("Abbreviation:", abbreviations(), panel);
        JPanel finalTemplatePanel = new JPanel(new GridLayout(1, 2));
        finalTemplatePanel.add(isFinal());
        finalTemplatePanel.add(isTemplate());
        layoutGenerator.addLabelWidgetPair("Is Final:", finalTemplatePanel, panel);
        layoutGenerator.addLabelWidgetPair("<html>Sectors:<br><br><br></html>", sectors(), panel);
        layoutGenerator.addLabelWidgetPair("Copied From:", template(), panel);
        layoutGenerator.addLabelWidgetPair("Last Modified By:     ", creator(), panel);

        layoutGenerator.makeCompactGrid(panel, 5, 2, 10, 10, 5, 10);

        return panel;
    }

    private JPanel createLowerSection() throws EmfException {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.GRAY));

        JPanel container = new JPanel();
        container.setLayout(new GridLayout(1, 2));
        container.add(createLowerLeftSection());
        container.add(createLowerRightSection());

        panel.add(container, BorderLayout.CENTER);

        return panel;
    }

    private JPanel createLowerLeftSection() throws EmfException {
        JPanel panel = new JPanel(new SpringLayout());
        SpringLayoutGenerator layoutGenerator = new SpringLayoutGenerator();

        layoutGenerator.addLabelWidgetPair("Model & Version:", modelToRun(), panel);
        layoutGenerator.addLabelWidgetPair("Modeling Region:", modRegions(), panel);
        layoutGenerator.addLabelWidgetPair("<html>Regions:<br><br><br></html>", regions(), panel);
        layoutGenerator.addLabelWidgetPair("Met/Emis Layers:", metEmisLayers(), panel);
        layoutGenerator.addLabelWidgetPair("Start Date & Time: ", startDate(), panel);

        layoutGenerator.makeCompactGrid(panel, 5, 2, 10, 10, 5, 10);

        return panel;
    }

    protected JPanel createLowerRightSection() throws EmfException {
        JPanel panel = new JPanel(new SpringLayout());
        SpringLayoutGenerator layoutGenerator = new SpringLayoutGenerator();

        layoutGenerator.addLabelWidgetPair("Downstream Model:", airQualityModels(), panel);
        layoutGenerator.addLabelWidgetPair("Speciation:", speciations(), panel);
        layoutGenerator.addLabelWidgetPair("Meteorological Year:", meteorlogicalYears(), panel);
        layoutGenerator.addLabelWidgetPair("Base Year:", emissionsYears(), panel);
        layoutGenerator.addLabelWidgetPair("Future Year:", futureYear(), panel);
        layoutGenerator.addLabelWidgetPair("End Date & Time:", endDate(), panel);

        layoutGenerator.makeCompactGrid(panel, 6, 2, 10, 10, 5, 10);

        return panel;
    }

    protected JLabel creator() {
        return createLeftAlignedLabel(caseObj.getLastModifiedBy().getName() + " on "
                + format(caseObj.getLastModifiedDate()));
    }

    private ScrollableComponent description() {
        description = new TextArea("description", caseObj.getDescription(), fieldWidth, 3);
       
        ScrollableComponent descScrollableTextArea = new ScrollableComponent(description);
        descScrollableTextArea.setPreferredSize(new Dimension(255, 80));
        return descScrollableTextArea;
    }

    private TextField name() {
        name = new TextField("name", this.fieldWidth);
        name.setText(caseObj.getName());
        name.setPreferredSize(defaultDimension);
        name.setToolTipText(caseObj.getName());

        return name;
    }

    private TextField futureYear() {
        futureYear = new TextField("Future Year", fieldWidth);
        futureYear.setToolTipText("This value is set for the environment variable 'FUTURE_YEAR'.");
        futureYear.setText(caseObj.getFutureYear() + "");
        futureYear.setPreferredSize(defaultDimension);

        return futureYear;
    }

    protected JPanel metEmisLayers() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        // panel.setBorder(BorderFactory.createEmptyBorder(0, 5, 5, 5));
        numMetLayers = new TextField("Num Met Layers", 11);
        numEmissionLayers = new TextField("Num Emis Layers", 11);

        numMetLayers.setText(caseObj.getNumMetLayers() != null ? caseObj.getNumMetLayers() + "" : "");
        numMetLayers.setToolTipText("Enter # of met layers");
        numMetLayers.setPreferredSize(defaultDimension); // new Dimension(255, 22));

        numEmissionLayers.setText(caseObj.getNumEmissionsLayers() != null ? caseObj.getNumEmissionsLayers() + "" : "");
        numEmissionLayers.setToolTipText("Enter # of emission layers");
        numEmissionLayers.setPreferredSize(defaultDimension);
        
        panel.add(numMetLayers);
        panel.add(new Label("empty", "  "));
        panel.add(numEmissionLayers);
        return panel;
    }

    protected TextField template() {
        template = new TextField("Template", fieldWidth);
        template.setText(caseObj.getTemplateUsed());
        template.setToolTipText(caseObj.getTemplateUsed());
        template.setEditable(false);
        template.setPreferredSize(defaultDimension);
        template.setMaximumSize(defaultDimension);

        return template;
    }

    protected JComponent isTemplate() {
        isTemplate = new CheckBox(" Is Template");
        isTemplate.setToolTipText("If checked, case is a template and will not run.");
        isTemplate.setSelected(caseObj.isCaseTemplate());

        return isTemplate;
    }

    protected JComponent isFinal() {
        isFinal = new CheckBox("");
        isFinal.setToolTipText("If checked, no changes should be made to the case.");
        isFinal.setSelected(caseObj.getIsFinal());

        return isFinal;
    }

    private EditableComboBox projects() throws EmfException {
        projectsCombo = new EditableComboBox(presenter.getProjects());
        projectsCombo.setSelectedItem(caseObj.getProject());
        projectsCombo.setPreferredSize(defaultDimension);
        addPopupMenuListener(projectsCombo, "projects");
         
        if ( session!=null && !session.user().isAdmin())
            projectsCombo.setEditable(false);
        return projectsCombo;
    }

    protected JPanel modelToRun() throws EmfException {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        Version version1 = new Version();
        version1.setName("213");
        ModelToRun runModel = caseObj.getModel();

        if (caseObj.getModel() == null)
            messagePanel.setMessage("Please specify model to run. ");
        modelToRunCombo = new ComboBox(presenter.getModelToRuns());
        modelToRunCombo.setToolTipText("This value is set for the environment variable 'MODEL_LABEL'.");
        modelToRunCombo.setSelectedItem(runModel);
        modelToRunCombo.setPreferredSize(new Dimension(122, 22));
        addPopupMenuListener(modelToRunCombo, "modeltoruns");
         
        modelVersionField = new TextField("modelVersion", fieldWidth / 2);
        modelVersionField.setToolTipText("This value is set for the environment variable 'MODEL_LABEL'.");
        modelVersionField.setText(caseObj.getModelVersion());
        modelVersionField.setPreferredSize(new Dimension(122, 22));
        addPopupMenuListener(modelToRunCombo, "modeltoruns");
        
        panel.add(modelToRunCombo);
        panel.add(new Label("empty", "   "));
        panel.add(modelVersionField);

        return panel;
    }

    protected ComboBox modRegions() throws EmfException {
        modRegionsCombo = new ComboBox(presenter.getRegions());
        modRegionsCombo.setSelectedItem(caseObj.getModelingRegion());
        modRegionsCombo.setPreferredSize(defaultDimension);
        
        return modRegionsCombo;
    }

    protected EditableComboBox abbreviations() throws EmfException {
        abbreviationsCombo = new EditableComboBox(presenter.getAbbreviations());
        abbreviationsCombo.setSelectedItem(caseObj.getAbbreviation());
        abbreviationsCombo.setPreferredSize(defaultDimension);
        addPopupMenuListener(abbreviationsCombo, "abbreviations");

        return abbreviationsCombo;
    }

    private EditableComboBox airQualityModels() throws EmfException {
        airQualityModelsCombo = new EditableComboBox(presenter.getAirQualityModels());
        airQualityModelsCombo.setToolTipText("This value is set for the environment variable 'EMF_AQM'.");
        airQualityModelsCombo.setSelectedItem(caseObj.getAirQualityModel());
        airQualityModelsCombo.setPreferredSize(defaultDimension);

        return airQualityModelsCombo;
    }

    private ComboBox categories() throws EmfException {
        categoriesCombo = new ComboBox(presenter.getCaseCategories());
        categoriesCombo.setSelectedItem(caseObj.getCaseCategory());
        categoriesCombo.setPreferredSize(defaultDimension);
        addPopupMenuListener(categoriesCombo, "categories");
        
        return categoriesCombo;
    }

    private JPanel sectors() throws EmfException {
        sectorsWidget = new AddRemoveSectorWidget(presenter.getAllSectors(), changeablesList, parentConsole);
        if (caseObj.getSectors() == null )
            sectorsWidget.setSectors(new Sector[0]);
        else 
            sectorsWidget.setSectors(caseObj.getSectors());
        sectorsWidget.setPreferredSize(new Dimension(255, 80));
        return sectorsWidget;
    }
    
    private JPanel regions() throws EmfException {
        regionsWidget = new AddRemoveRegionsWidget(presenter.getAllGeoRegions(), changeablesList, parentConsole);
        regionsWidget.setRegions(caseObj.getRegions());
        regionsWidget.setPreferredSize(new Dimension(255, 80));
        regionsWidget.setDesktopManager(desktopManager);
        regionsWidget.setEmfSession(session);
        regionsWidget.observeParentPresenter(presenter);
        return regionsWidget;
    }

    private EditableComboBox emissionsYears() throws EmfException {
        emissionsYearCombo = new EditableComboBox(presenter.getEmissionsYears());
        emissionsYearCombo.setToolTipText("This value is set for the environment variable 'BASE_YEAR'.");
        emissionsYearCombo.setSelectedItem(caseObj.getEmissionsYear());
        emissionsYearCombo.setPreferredSize(defaultDimension);
        
        return emissionsYearCombo;
    }

    private EditableComboBox meteorlogicalYears() throws EmfException {
        meteorlogicalYearCombo = new EditableComboBox(presenter.getMeteorlogicalYears());
        meteorlogicalYearCombo.setSelectedItem(caseObj.getMeteorlogicalYear());
        meteorlogicalYearCombo.setPreferredSize(defaultDimension);
        
        return meteorlogicalYearCombo;
    }

    private EditableComboBox speciations() throws EmfException {
        speciationCombo = new EditableComboBox(presenter.getSpeciations());
        speciationCombo.setToolTipText("This value is set for the environment variable 'EMF_SPC'.");
        speciationCombo.setSelectedItem(caseObj.getSpeciation());
        speciationCombo.setPreferredSize(defaultDimension);

        return speciationCombo;
    }

    private ComboBox runStatus() {
        runStatusCombo = new ComboBox(RunStatuses.all());
        runStatusCombo.setPreferredSize(defaultDimension);
        if (caseObj.getRunStatus() == null) {
            runStatusCombo.setSelectedIndex(0);
        } else {
            runStatusCombo.setSelectedItem(caseObj.getRunStatus());
        }
      
        return runStatusCombo;
    }

    protected TextField startDate() {
        startDate = new TextField("Start Date", fieldWidth);
        startDate.setToolTipText("Value (MM/dd/yyyy HH:mm) set to parameter environment variable 'EPI_STDATE_TIME'.");
        startDate.setText(format(caseObj.getStartDate()) + "");
        startDate.setPreferredSize(defaultDimension);

        return startDate;
    }

    private TextField endDate() {
        endDate = new TextField("End Date", fieldWidth);
        endDate.setToolTipText("Value (MM/dd/yyyy HH:mm) set to parameter environment variable 'EPI_ENDATE_TIME'.");
        endDate.setText(format(caseObj.getEndDate()) + "");
        endDate.setPreferredSize(defaultDimension);

        return endDate;
    }

    protected void addPopupMenuListener(final JComboBox box, final String toget) {
        box.addPopupMenuListener(new PopupMenuListener() {
            public void popupMenuCanceled(PopupMenuEvent event) {
                // NOTE Auto-generated method stub
            }

            public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {
                // NOTE Auto-generated method stub
            }

            public void popupMenuWillBecomeVisible(PopupMenuEvent event) {
                try {
                    Object selected = box.getSelectedItem();
                    box.setModel(new DefaultComboBoxModel(getAllObjects(toget)));
                    box.setSelectedItem(selected);
                } catch (Exception e) {
                    e.printStackTrace();
                    messagePanel.setError(e.getMessage());
                }
            }
        });
    }

    protected Object[] getAllObjects(String toget) throws EmfException {
        if (toget.equals("categories"))
            return presenter.getCaseCategories();

        else if (toget.equals("abbreviations"))
            return presenter.getAbbreviations();

        else if (toget.equals("projects"))
            return presenter.getProjects();

        else if (toget.equals("modeltoruns"))
            return presenter.getModelToRuns();

        return new Object[0];

    }

    private String format(Date date) {
        return CustomDateFormat.format_MM_DD_YYYY_HH_mm(date);
    }

    private JLabel createLeftAlignedLabel(String name) {
        JLabel label = new JLabel(name);
        label.setToolTipText(name);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);

        return label;
    }

    public void save() throws EmfException {
        if (categoriesCombo.getSelectedItem() == null)
            throw new EmfException("Please select a valid case category from the Summary tab.");
        
        caseObj.setName(name.getText().trim());
        saveFutureYear();
        caseObj.setDescription(description.getText());
        caseObj.setCaseTemplate(isTemplate.isSelected());
        caseObj.setIsFinal(isFinal.isSelected());
        caseObj.setProject(presenter.getProject(projectsCombo.getSelectedItem()));
        caseObj.setModelingRegion((Region) modRegionsCombo.getSelectedItem());
        caseObj.setNumMetLayers(validateInt(numMetLayers));
        caseObj.setNumEmissionsLayers(validateInt(numEmissionLayers));
        updateAbbreviation(caseObj);
        caseObj.setAirQualityModel(presenter.getAirQualityModel(airQualityModelsCombo.getSelectedItem()));
        caseObj.setCaseCategory(presenter.getCaseCategory(categoriesCombo.getSelectedItem()));
        caseObj.setEmissionsYear(presenter.getEmissionsYear(emissionsYearCombo.getSelectedItem()));
        caseObj.setMeteorlogicalYear(presenter.getMeteorlogicalYear(meteorlogicalYearCombo.getSelectedItem()));
        caseObj.setSpeciation(presenter.getSpeciation(speciationCombo.getSelectedItem()));
        caseObj.setRunStatus(runStatusCombo.getSelectedItem() + "");
        saveStartDate();
        saveEndDate();
        caseObj.setSectors(sectorsWidget.getSectors());
        caseObj.setRegions(regionsWidget.getRegions());
        caseObj.setModel(presenter.getModelToRun(modelToRunCombo.getSelectedItem()));
        caseObj.setModelVersion((modelVersionField.getText() == null) ? "" : modelVersionField.getText().trim());
    }

    private void updateAbbreviation(Case caseObj) throws EmfException {
        Abbreviation existed = caseObj.getAbbreviation();
        Object selected = abbreviationsCombo.getSelectedItem();
        
        if (existed != null && selected instanceof String) {
            existed.setName(selected.toString());
            caseObj.setAbbreviation(existed);
            return;
        }       
        caseObj.setAbbreviation(presenter.getAbbreviation(selected));
    }

    private Integer validateInt(TextField value) throws EmfException {
        NumberFieldVerifier verifier = new NumberFieldVerifier("Case Summary tab: ");
        if (value.getText().trim().length() > 0)
            return verifier.parseInteger(value);
        return null;
    }

    private void saveFutureYear() throws EmfException {
        String year = futureYear.getText().trim();
        if (year.length() == 0 || year.equals("0")) {
            caseObj.setFutureYear(0);
            return;
        }
        YearValidation validation = new YearValidation("Future Year");
        caseObj.setFutureYear(validation.value(futureYear.getText()));
    }

    private void saveEndDate() throws EmfException {
        try {
            String date = startDate.getText().trim();
            if (date.length() == 0) {
                caseObj.setStartDate(null);
                return;
            }
            caseObj.setStartDate(CustomDateFormat.parse_MM_DD_YYYY_HH_mm(startDate.getText()));
        } catch (ParseException e) {
            throw new EmfException("Please enter the Start Date in the correct format (MM/dd/yyyy HH:mm)");
        }
    }

    private void saveStartDate() throws EmfException {
        try {
            String date = endDate.getText().trim();
            if (date.length() == 0) {
                caseObj.setEndDate(null);
                return;
            }
            caseObj.setEndDate(CustomDateFormat.parse_MM_DD_YYYY_HH_mm(endDate.getText()));
        } catch (ParseException e) {
            throw new EmfException("Please enter the End Date in the correct format (MM/dd/yyyy HH:mm)");
        }
    }

    public void observe(EditableCaseSummaryTabPresenter presenter) {
        this.presenter = presenter;
    }

    
    public void doRefresh() throws EmfException {
        new RefreshSwingWorkerTasks(layout, messagePanel, presenter).execute();
    }
    
    public void refresh(Case caseObj) {
        this.caseObj = caseObj;
        super.removeAll();
        try {
            setLayout();
        } catch (EmfException e) {
            // NOTE Auto-generated catch block
            e.printStackTrace();
        }
        super.validate();
        changeablesList.resetChanges();
    }

    public void addSector(Sector sector) {
        if (sector == null)
            return;

        List<Sector> sectors = new ArrayList<Sector>();
        sectors.addAll(Arrays.asList(sectorsWidget.getSectors()));
        boolean found = false;

        for (Iterator<Sector> iter = sectors.iterator(); iter.hasNext();) {
            Sector item = iter.next();
            if (sector != null && sector.equals(item))
                found = true;
        }

        if (!found) {
            sectorsWidget.addSector(sector);
            String msg = messagePanel.getMessage();
            
            if (msg == null || (msg != null && msg.toUpperCase().contains("SAVED")))
                msg = "";
                    
            messagePanel.setMessage(msg + " Sector \"" + sector.getName() + "\" added to the summary tab.");
        }
    }
    
    public void addRegion(GeoRegion region) {
        if (region == null)
            return;

        List<GeoRegion> regions = new ArrayList<GeoRegion>();
        regions.addAll(Arrays.asList(regionsWidget.getRegions()));
        boolean found = false;

        for (Iterator<GeoRegion> iter = regions.iterator(); iter.hasNext();) {
            GeoRegion item = iter.next();
            if (region != null && region.equals(item))
                found = true;
        }

        if (!found) {
            regionsWidget.addRegion(region);
            String msg = messagePanel.getMessage();
            
            if (msg == null || (msg != null && msg.toUpperCase().contains("SAVED")))
                msg = "";
                    
            messagePanel.setMessage(msg + " Region \"" + region.getName() + "\" added to the summary tab.");
        }
    }
    
    public void setDesktopManager(DesktopManager dm) {
        this.desktopManager = dm;
    }

    public void updateDescriptionTextArea(String descText) { // BUG3621
        // NOTE Auto-generated method stub
        this.description.setText( descText);
    }

    public String getDescription() {
        return description.getText();
    }

    public void setDescription(String description) {
        this.description.setText( description);
    }
    
    private void addChangables(){
        changeablesList.addChangeable(description);
        changeablesList.addChangeable(name);
        changeablesList.addChangeable(name);
        changeablesList.addChangeable(numMetLayers);
        changeablesList.addChangeable(numEmissionLayers);
        changeablesList.addChangeable(numEmissionLayers);
        changeablesList.addChangeable(modelToRunCombo);
        changeablesList.addChangeable(modelVersionField);
        changeablesList.addChangeable(modRegionsCombo);
        changeablesList.addChangeable(abbreviationsCombo);

        changeablesList.addChangeable(airQualityModelsCombo);
        changeablesList.addChangeable(categoriesCombo);
        changeablesList.addChangeable(emissionsYearCombo);
        changeablesList.addChangeable(meteorlogicalYearCombo);
        changeablesList.addChangeable(speciationCombo);
        changeablesList.addChangeable(runStatusCombo);
        changeablesList.addChangeable(startDate);
        changeablesList.addChangeable(endDate);
    }
}
