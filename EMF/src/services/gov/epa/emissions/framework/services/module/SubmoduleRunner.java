package gov.epa.emissions.framework.services.module;

import gov.epa.emissions.commons.data.DatasetType;
import gov.epa.emissions.commons.data.InternalSource;
import gov.epa.emissions.commons.db.Datasource;
import gov.epa.emissions.commons.db.DbServer;
import gov.epa.emissions.commons.io.TableFormat;
import gov.epa.emissions.commons.security.User;
import gov.epa.emissions.commons.util.CustomDateFormat;
import gov.epa.emissions.framework.services.DbServerFactory;
import gov.epa.emissions.framework.services.EmfException;
import gov.epa.emissions.framework.services.data.DatasetDAO;
import gov.epa.emissions.framework.services.data.EmfDataset;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;

abstract class SubmoduleRunner extends ModuleRunner {
    private ModuleRunner parentModuleRunner;
    private ModuleTypeVersionSubmodule moduleTypeVersionSubmodule;
    private HistorySubmodule historySubmodule;
    
    private Date submoduleStartDate;
    
    private String path = null;
    private String pathNames = null;
    private int inputDatasetsCount = 0;
    private int inputParametersCount = 0;
    
    public SubmoduleRunner(ModuleRunnerContext moduleRunnerContext, ModuleRunner parentModuleRunner, ModuleTypeVersionSubmodule moduleTypeVersionSubmodule) {
        super(moduleRunnerContext);
        this.parentModuleRunner = parentModuleRunner;
        this.moduleTypeVersionSubmodule = moduleTypeVersionSubmodule;
        
        inputDatasetsCount = 0;
        for (ModuleTypeVersionDataset moduleTypeVersionDataset : moduleTypeVersionSubmodule.getModuleTypeVersion().getModuleTypeVersionDatasets().values())
            if (!moduleTypeVersionDataset.getMode().equals(ModuleTypeVersionDataset.OUT)) // IN & INOUT
                inputDatasetsCount++;
        
        inputParametersCount = 0;
        for (ModuleTypeVersionParameter moduleTypeVersionParameter : moduleTypeVersionSubmodule.getModuleTypeVersion().getModuleTypeVersionParameters().values())
            if (!moduleTypeVersionParameter.getMode().equals(ModuleTypeVersionParameter.OUT)) // IN & INOUT
                inputParametersCount++;
    }

    protected void start() {
        submoduleStartDate = new Date();
    }

    protected void createSubmoduleHistory() {
        historySubmodule = new HistorySubmodule();
        historySubmodule.setHistory(getHistory());
        historySubmodule.setCreationDate(getStartDate());
        historySubmodule.setSubmodulePath(getPath());
        historySubmodule.setSubmodulePathNames(getPathNames());
        historySubmodule.setStatus(History.STARTED);
        String logMessage = String.format("Submodule '%s' started by %s on %s",
                                          historySubmodule.getSubmodulePathNames(), getUser().getName(),
                                          CustomDateFormat.format_yyyy_MM_dd_HHmmssSSS(getStartDate()));
        historySubmodule.addLogMessage(History.INFO, logMessage);
        getHistory().addHistorySubmodule(historySubmodule);
        getModulesDAO().updateHistory(getHistory(), getEntityManager());
    }

    protected void stop() {
        Date submoduleStopDate = new Date();
        long durationSeconds = (submoduleStopDate.getTime() - submoduleStartDate.getTime()) / 1000;
        historySubmodule.setDurationSeconds((int)durationSeconds);
        historySubmodule = getModulesDAO().updateHistorySubmodule(historySubmodule, getEntityManager());
    }

    public void run() throws EmfException {
        start();
        createSubmoduleHistory();
        execute();
        stop();
    }

    protected String getNewInternalDatasetName(ModuleTypeVersionDataset moduleTypeVersionDataset) {
        String tempName = getModule().getName() + " #" + getHistory().getRunId() +  " " + getPathNames();
        if (tempName.length() > 245) {
            tempName = getModule().getName() + " #" + getHistory().getRunId() + " ... / " + moduleTypeVersionSubmodule.getName() + " / " + moduleTypeVersionDataset.getPlaceholderName();
        }
        if (tempName.length() > 245) {
            tempName = getModule().getName() + " #" + getHistory().getRunId() + " " + moduleTypeVersionDataset.getPlaceholderName();
        }
        if (tempName.length() > 245) {
            tempName = getModule().getName() + " #" + getHistory().getRunId();
        }
        if (tempName.length() > 245) {
            tempName = tempName.substring(0, 245);
        }
        String name = "";
        EmfDataset dataset = null;
        do {
            name = tempName + " " + CustomDateFormat.format_HHMMSSSS(new Date()); 
            dataset = getDatasetDAO().getDataset(getEntityManager(), tempName);
        } while (dataset != null);
        return name;
    }

    protected void createDatasets() throws Exception {
        
        DbServer dbServer = getDbServer();
        User user = getUser();
        EntityManagerFactory entityManagerFactory = getEntityManagerFactory();
        DbServerFactory dbServerFactory = getDbServerFactory();
        Datasource datasource = getDatasource();
        Connection connection = getConnection();
        DatasetDAO datasetDAO = getDatasetDAO();
        EntityManager entityManager = getEntityManager();
        Date startDate = getStartDate();
        
        Module module = getModule();
        Map<String, ModuleInternalDataset> moduleInternalDatasets = module.getModuleInternalDatasets();

        History history = getHistory();
        Map<String, HistoryInternalDataset> historyInternalDatasets = history.getHistoryInternalDatasets();
        
        ModuleTypeVersion moduleTypeVersion = moduleTypeVersionSubmodule.getModuleTypeVersion();
        
        String logMessage = "";
        String errorMessage = "";
        
        StringBuilder warnings = new StringBuilder();
        
        for(ModuleTypeVersionDataset moduleTypeVersionDataset : moduleTypeVersion.getModuleTypeVersionDatasets().values()) {
            if (!moduleTypeVersionDataset.getMode().equals(ModuleTypeVersionDataset.OUT))
                continue;
            String placeholderName = moduleTypeVersionDataset.getPlaceholderName();
            String placeholderPath = getPath(placeholderName);
            String placeholderPathNames = getPathNames(placeholderName);
            DatasetType datasetType = moduleTypeVersionDataset.getDatasetType();
            boolean keepInternalDataset = false;
            String internalDatasetName = "";
            if (moduleInternalDatasets.containsKey(placeholderPath)) {
                ModuleInternalDataset moduleInternalDataset = moduleInternalDatasets.get(placeholderPath);
                keepInternalDataset = moduleInternalDataset.getKeep();
                String datasetNamePattern = moduleInternalDataset.getDatasetNamePattern();
                if (datasetNamePattern == null || datasetNamePattern.trim().length() == 0) {
                    internalDatasetName = getNewInternalDatasetName(moduleTypeVersionDataset);
                } else {
                    internalDatasetName = getNewDatasetName(datasetNamePattern, user, startDate, history);
                }
            } else {
                keepInternalDataset = false;
                internalDatasetName = getNewInternalDatasetName(moduleTypeVersionDataset);
            }
            String persistence = keepInternalDataset ? "persistent" : "temporary";
            EmfDataset dataset = getDatasetDAO().getDataset(entityManager, internalDatasetName);
            int versionNumber = 0;
            if (dataset == null) { // NEW
                TableFormat tableFormat = getTableFormat(moduleTypeVersionDataset, dbServer);
                String description = "New internal dataset created by the '" + module.getName() + "' module for the '" + placeholderPathNames + "' placeholder.";
                DatasetCreator datasetCreator = new DatasetCreator(module, placeholderPathNames, user, entityManagerFactory, dbServerFactory, datasource);
                dataset = datasetCreator.addDataset("mod", internalDatasetName, datasetType, module.getIsFinal(), tableFormat, description);
               
                InternalSource internalSource = getInternalSource(dataset);
                
                logMessage = String.format("Created new %s internal dataset for %s placeholder %s:\n  * dataset type: '%s'\n  * dataset name: '%s'\n  * table name: '%s'\n  * version: %d",
                                           persistence, moduleTypeVersionDataset.getMode(), placeholderPathNames,
                                           internalSource.getType(), dataset.getName(), internalSource.getTable(), versionNumber);
                historySubmodule.addLogMessage(History.INFO, logMessage);

                setOutputDataset(placeholderName, new DatasetVersion(dataset, versionNumber, keepInternalDataset));
            } else if (!dataset.getDatasetType().equals(datasetType)) { // different dataset type
                throw new EmfException("Dataset \"" + dataset.getName() +
                                       "\" already exists and can't be replaced because it has a different dataset type (\"" +
                                       dataset.getDatasetType().getName() + "\" instead of \"" +
                                       datasetType.getName() + "\")");
            } else if (!wasDatasetCreatedByModule(dataset, module, placeholderPathNames)) { // dataset was not created by this module
                throw new EmfException("Can't replace internal dataset \"" + dataset.getName() +
                                       "\" because it was not created by module \"" + module.getName() +
                                       "\" for the \"" + placeholderPathNames + "\" placeholder");
            } else { // REPLACE
                String datasetName = dataset.getName();
                
                checkDatasetReplacementRules(warnings, getModuleRunnerContext(), dataset, module);
                historySubmodule.addLogMessage(History.INFO, String.format("Dataset '%s' will be replaced.", datasetName));
                if (warnings.length() > 0)
                    historySubmodule.addLogMessage(History.WARNING, warnings.toString());

                boolean must_unlock = false;
                if (!dataset.isLocked()) {
                    dataset = datasetDAO.obtainLocked(user, dataset, entityManager);
                    must_unlock = true;
                } else if (!dataset.isLocked(user)) {
                    errorMessage = String.format("Could not replace internal dataset '%s' for placeholder '%s'. The dataset is locked by %s.",
                                                  datasetName, placeholderPathNames, dataset.getLockOwner());
                    throw new EmfException(errorMessage);
                }
                
                try {
                    DatasetCreator datasetCreator = new DatasetCreator(module, placeholderPathNames, user, entityManagerFactory, dbServerFactory, datasource);
                    datasetCreator.replaceDataset(entityManager, connection, dataset, module.getIsFinal());
                } finally {
                    if (must_unlock)
                        dataset = datasetDAO.releaseLocked(user, dataset, entityManager);
                }
                
                InternalSource internalSource = getInternalSource(dataset);
                
                logMessage = String.format("Replacing internal dataset for %s placeholder %s:\n  * dataset type: '%s'\n  * dataset name: '%s'\n  * table name: '%s'\n  * version: %d",
                                           moduleTypeVersionDataset.getMode(), placeholderPathNames,
                                           internalSource.getType(), dataset.getName(), internalSource.getTable(), versionNumber);
                historySubmodule.addLogMessage(History.INFO, logMessage);
                
                setOutputDataset(placeholderName, new DatasetVersion(dataset, versionNumber, keepInternalDataset));
            }
            
            if (keepInternalDataset) {
                HistoryInternalDataset historyInternalDataset = new HistoryInternalDataset();
                historyInternalDataset.setHistory(history);
                historyInternalDataset.setPlaceholderPath(placeholderPath);
                historyInternalDataset.setPlaceholderPathNames(placeholderPathNames);
                historyInternalDataset.setDatasetId(dataset.getId());
                historyInternalDataset.setVersion(versionNumber);
                historyInternalDatasets.put(placeholderPath, historyInternalDataset);
            }
        }
        getModulesDAO().updateHistory(getHistory(), getEntityManager());
    }

    public ModuleRunner getParentModuleRunner() {
        return parentModuleRunner;
    }

    public History getHistory() {
        return parentModuleRunner.getHistory();
    }

    public ModuleTypeVersionSubmodule getModuleTypeVersionSubmodule() {
        return moduleTypeVersionSubmodule;
    }

    public int getId() {
        return moduleTypeVersionSubmodule.getId();
    }

    public String getPath() {
        if (path == null) {
            path = parentModuleRunner.getPath();
            if (path == null || path.trim().isEmpty()) {
                path = "" + moduleTypeVersionSubmodule.getId();
            } else {
                path = path + "/" + moduleTypeVersionSubmodule.getId();
            }
        }
        return path;
    }

    public String getPathNames() {
        if (pathNames == null) {
            pathNames = parentModuleRunner.getPathNames();
            if (pathNames == null || pathNames.trim().isEmpty()) {
                pathNames = moduleTypeVersionSubmodule.getName();
            } else {
                pathNames = pathNames + " / " + moduleTypeVersionSubmodule.getName();
            }
        }
        return pathNames;
    }

    public HistorySubmodule getHistorySubmodule() {
        return historySubmodule;
    }

    public boolean isReady() throws EmfException {
        Map<String, DatasetVersion> inputDatasets = getInputDatasets();
        if (inputDatasets.size() < inputDatasetsCount) // quick check optimization
            return false;
        if (inputDatasets.size() > inputDatasetsCount)
            throw new EmfException("Internal error: two many input datasets (" + inputDatasets.size() + " instead of " + inputDatasetsCount + ")");
        Map<String, DatasetVersion> outputDatasets = getOutputDatasets();
        for (ModuleTypeVersionDataset moduleTypeVersionDataset : moduleTypeVersionSubmodule.getModuleTypeVersion().getModuleTypeVersionDatasets().values()) {
            if (!moduleTypeVersionDataset.getMode().equals(ModuleTypeVersionDataset.OUT)) { // IN & INOUT
                if (!inputDatasets.containsKey(moduleTypeVersionDataset.getPlaceholderName()))
                    throw new EmfException("Internal error: the input dataset for " + moduleTypeVersionDataset.getPlaceholderName() + " IN/INOUT placeholder is missing");
            }
            if (moduleTypeVersionDataset.getMode().equals(ModuleTypeVersionDataset.INOUT)) { // INOUT
                if (!outputDatasets.containsKey(moduleTypeVersionDataset.getPlaceholderName()))
                    throw new EmfException("Internal error: the output dataset for " + moduleTypeVersionDataset.getPlaceholderName() + " INOUT placeholder is missing");
            }
        }

        Map<String, String> inputParameters = getInputParameters();
        if (inputParameters.size() < inputParametersCount) // quick check optimization
            return false;
        if (inputParameters.size() > inputParametersCount)
            throw new EmfException("Internal error: two many input parameters (" + inputParameters.size() + " instead of " + inputParametersCount + ")");
        Map<String, String> outputParameters = getOutputParameters();
        for (ModuleTypeVersionParameter moduleTypeVersionParameter : moduleTypeVersionSubmodule.getModuleTypeVersion().getModuleTypeVersionParameters().values()) {
            if (!moduleTypeVersionParameter.getMode().equals(ModuleTypeVersionParameter.OUT)) { // IN & INOUT
                if (!inputParameters.containsKey(moduleTypeVersionParameter.getParameterName()))
                    throw new EmfException("Internal error: the input for " + moduleTypeVersionParameter.getParameterName() + " IN/INOUT parameter is missing");
            }
            if (moduleTypeVersionParameter.getMode().equals(ModuleTypeVersionParameter.INOUT)) { // INOUT
                if (!outputParameters.containsKey(moduleTypeVersionParameter.getParameterName()))
                    throw new EmfException("Internal error: the output for " + moduleTypeVersionParameter.getParameterName() + " INOUT parameter is missing");
            }
        }
        
        return true;
    }

    protected void executeTeardownScript(List<String> outputDatasetTables) throws EmfException {
        Connection connection = getConnection();
        ModulesDAO modulesDAO = getModulesDAO();
        EntityManager entityManager = getEntityManager();

        String teardownScript = getDenyPermissionsScript(getUserTimeStamp(), outputDatasetTables);

        Statement statement = null;
        try {
            historySubmodule.setTeardownScript(teardownScript);
            
            historySubmodule.addLogMessage(History.INFO, "Starting teardown script.");
            
            historySubmodule = modulesDAO.updateHistorySubmodule(historySubmodule, entityManager);
            
            statement = connection.createStatement();
            statement.execute(teardownScript);
            
        } catch (Exception e) {
            throw new EmfException(TEARDOWN_SCRIPT_ERROR + e.getMessage());
        } finally {
            if (statement != null) {
                try {
                    statement.close();
                } catch (SQLException e) {
                    // ignore
                }
                statement = null;
            }
        }
    }

    public Date getSubmoduleStartDate() {
        return submoduleStartDate;
    }
}
