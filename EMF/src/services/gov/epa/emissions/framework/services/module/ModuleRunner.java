package gov.epa.emissions.framework.services.module;

import gov.epa.emissions.commons.data.DatasetType;
import gov.epa.emissions.commons.data.InternalSource;
import gov.epa.emissions.commons.data.KeyVal;
import gov.epa.emissions.commons.db.Datasource;
import gov.epa.emissions.commons.db.DbServer;
import gov.epa.emissions.commons.db.SqlDataTypes;
import gov.epa.emissions.commons.db.version.Version;
import gov.epa.emissions.commons.db.version.Versions;
import gov.epa.emissions.commons.io.Column;
import gov.epa.emissions.commons.io.FileFormat;
import gov.epa.emissions.commons.io.TableFormat;
import gov.epa.emissions.commons.io.other.ProjectionPacketFileFormat;
import gov.epa.emissions.commons.io.temporal.VersionedTableFormat;
import gov.epa.emissions.commons.security.User;
import gov.epa.emissions.commons.util.CustomDateFormat;
import gov.epa.emissions.framework.services.DbServerFactory;
import gov.epa.emissions.framework.services.EmfDbServer;
import gov.epa.emissions.framework.services.EmfException;
import gov.epa.emissions.framework.services.InfrastructureException;
import gov.epa.emissions.framework.services.basic.StatusDAO;
import gov.epa.emissions.framework.services.data.DatasetDAO;
import gov.epa.emissions.framework.services.data.EmfDataset;
import gov.epa.emissions.framework.services.persistence.DataSourceFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;

import org.apache.tomcat.dbcp.dbcp2.BasicDataSource;

abstract class ModuleRunner {
    
    private ModuleRunnerContext moduleRunnerContext;
    
    public final static String    SETUP_SCRIPT_ERROR = "Failed to execute setup script.\n";
    public final static String     USER_SCRIPT_ERROR = "Failed to execute module.\n";
    public final static String       SUBMODULE_ERROR = "Failed to execute submodule.\n";
    public final static String TEARDOWN_SCRIPT_ERROR = "Failed to execute teardown script.\n";
    
    private Map<String, DatasetVersion> inputDatasets;  // the keys are the placeholder names 
    private Map<String, DatasetVersion> outputDatasets; // inout datasets are added to both
    
    private Map<String, String> inputParameters;  // the keys are the parameter names
    private Map<String, String> outputParameters; // inout parameters are added to both
    
    private String finalStatusMessage;
    
    public ModuleRunner(ModuleRunnerContext moduleRunnerContext) {
        this.moduleRunnerContext = moduleRunnerContext;
        
        inputDatasets = new HashMap<String, DatasetVersion>(); 
        outputDatasets = new HashMap<String, DatasetVersion>();
        
        inputParameters = new HashMap<String, String>();
        outputParameters = new HashMap<String, String>();
        
        finalStatusMessage = "";
    }
    
    public static TableFormat getTableFormat(ModuleTypeVersionDataset moduleTypeVersionDataset, DbServer dbServer) throws EmfException {
        SqlDataTypes sqlDataTypes = dbServer.getSqlDataTypes();
        DatasetType datasetType = moduleTypeVersionDataset.getDatasetType();
        FileFormat fileFormat = datasetType.getFileFormat();
        if (fileFormat == null) {
            if (datasetType.getName().equals(DatasetType.projectionPacket)) {
                fileFormat = new ProjectionPacketFileFormat(sqlDataTypes);
            } else {
                throw new EmfException(String.format("Dataset type \"%s\" used by \"%s\" placeholder is not supported",
                                                     datasetType.getName(), moduleTypeVersionDataset.getPlaceholderName())); 
            }
        }
        VersionedTableFormat versionedTableFormat = new VersionedTableFormat(fileFormat, sqlDataTypes);
        return versionedTableFormat;
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
        
        Module module = getModule();
        History history = getHistory();
        Map<String, HistoryDataset> historyDatasets = history.getHistoryDatasets();
        Date startDate = getStartDate();
        
        String logMessage = "";
        String errorMessage = "";
        
        StringBuilder warnings = new StringBuilder();
        
        for(ModuleDataset moduleDataset : module.getModuleDatasets().values()) {
            ModuleTypeVersionDataset moduleTypeVersionDataset = moduleDataset.getModuleTypeVersionDataset();
            String placeholderName = moduleTypeVersionDataset.getPlaceholderName();
            EmfDataset dataset = null;
            int versionNumber = 0;
            if (moduleTypeVersionDataset.getMode().equals(ModuleTypeVersionDataset.OUT)) {
                if (moduleDataset.getOutputMethod().equals(ModuleDataset.NEW)) {
                    DatasetType datasetType = moduleTypeVersionDataset.getDatasetType();
                    DatasetCreator datasetCreator = new DatasetCreator(module, placeholderName, user, entityManagerFactory, dbServerFactory, datasource);
                    String newDatasetName = getNewDatasetName(moduleDataset.getDatasetNamePattern(), user, startDate, history);
                    boolean newDataset = true;
                    dataset = datasetDAO.getDataset(entityManager, newDatasetName);
                    if (dataset == null) { // dataset doesn't exists, create NEW
                        TableFormat tableFormat = getTableFormat(moduleTypeVersionDataset, dbServer);
                        String description = "New dataset created by the '" + module.getName() + "' module for the '" + placeholderName + "' placeholder.";
                        dataset = datasetCreator.addDataset("mod", newDatasetName, datasetType, module.getIsFinal(), tableFormat, description);
                        newDataset = true;
                    } else if (!dataset.getDatasetType().equals(datasetType)) { // different dataset type
                        throw new EmfException("Dataset \"" + dataset.getName() +
                                               "\" already exists and can't be replaced because it has a different dataset type (\"" +
                                               dataset.getDatasetType().getName() + "\" instead of \"" +
                                               datasetType.getName() + "\")");
                    } else if (!wasDatasetCreatedByModule(dataset, module, placeholderName)) { // dataset was not created by this module
                        throw new EmfException("Dataset \"" + dataset.getName() +
                                               "\" already exists and can't be replaced because it was not created by module \"" + module.getName() +
                                               "\" for the \"" + placeholderName + "\" placeholder");
                    } else {
                        checkDatasetReplacementRules(warnings, moduleRunnerContext, dataset, module);
                        history.addLogMessage(History.INFO, String.format("Dataset '%s' will be replaced.", dataset.getName()));
                        if (warnings.length() > 0)
                            history.addLogMessage(History.WARNING, warnings.toString());
                        boolean must_unlock = false;
                        if (!dataset.isLocked()) {
                            dataset = datasetDAO.obtainLocked(user, dataset, entityManager);
                            must_unlock = true;
                        } else if (!dataset.isLocked(user)) {
                            errorMessage = String.format("Could not replace dataset '%s' for placeholder '%s'. The dataset is locked by %s.",
                                                         newDatasetName, placeholderName, dataset.getLockOwner());
                            throw new EmfException(errorMessage);
                        }
                        
                        try {
                            datasetCreator.replaceDataset(entityManager, connection, dataset, module.getIsFinal());
                            newDataset = false;
                        } finally {
                            if (must_unlock)
                                dataset = datasetDAO.releaseLocked(user, dataset, entityManager);
                        }
                    }
                    
                    InternalSource internalSource = getInternalSource(dataset);
                    
                    logMessage = String.format("%s dataset for %s %s placeholder %s:\n  * dataset type: '%s'\n  * dataset name: '%s'\n  * table name: '%s'\n  * version: %d",
                                               newDataset ? "Created new" : "Replacing", moduleTypeVersionDataset.getMode(), moduleDataset.getOutputMethod(), placeholderName,
                                               internalSource.getType(), dataset.getName(), internalSource.getTable(), versionNumber);
                    history.addLogMessage(History.INFO, logMessage);

                    setOutputDataset(placeholderName, new DatasetVersion(dataset, versionNumber));
                } else { // REPLACE
                    dataset = datasetDAO.getDataset(entityManagerFactory.createEntityManager(), moduleDataset.getDatasetId());
                    if (dataset == null) {
                        errorMessage = String.format("Failed to find dataset with ID %d for placeholder '%s'.",
                                                     moduleDataset.getDatasetId(), placeholderName);
                        throw new EmfException(errorMessage);
                    }
                    String datasetName = dataset.getName();
                    
                    checkDatasetReplacementRules(warnings, moduleRunnerContext, dataset, module);
                    history.addLogMessage(History.INFO, String.format("Dataset '%s' will be replaced.", dataset.getName()));
                    if (warnings.length() > 0)
                        history.addLogMessage(History.WARNING, warnings.toString());

                    boolean must_unlock = false;
                    if (!dataset.isLocked()) {
                        dataset = datasetDAO.obtainLocked(user, dataset, entityManager);
                        must_unlock = true;
                    } else if (!dataset.isLocked(user)) {
                        errorMessage = String.format("Could not replace dataset '%s' for placeholder '%s'. The dataset is locked by %s.",
                                                      datasetName, placeholderName, dataset.getLockOwner());
                        throw new EmfException(errorMessage);
                    }
                    
                    try {
                        DatasetCreator datasetCreator = new DatasetCreator(module, placeholderName, user, entityManagerFactory, dbServerFactory, datasource);
                        datasetCreator.replaceDataset(entityManager, connection, dataset, module.getIsFinal());
                    } finally {
                        if (must_unlock)
                            dataset = datasetDAO.releaseLocked(user, dataset, entityManager);
                    }
                    
                    InternalSource internalSource = getInternalSource(dataset);
                    
                    logMessage = String.format("Replacing dataset for %s %s placeholder %s:\n  * dataset type: '%s'\n  * dataset name: '%s'\n  * table name: '%s'\n  * version: %d",
                                               moduleTypeVersionDataset.getMode(), moduleDataset.getOutputMethod(), placeholderName,
                                               internalSource.getType(), dataset.getName(), internalSource.getTable(), versionNumber);
                    history.addLogMessage(History.INFO, logMessage);
                    
                    setOutputDataset(placeholderName, new DatasetVersion(dataset, versionNumber));
                }
            } else { // IN or INOUT
                boolean isOptional = moduleDataset.isOptional();
                if (moduleDataset.getDatasetId() == null) {
                    if (isOptional) {
                        logMessage = String.format("Optional %s dataset placeholder %s is not bound to a dataset",
                                                   moduleTypeVersionDataset.getMode(), placeholderName);
                        history.addLogMessage(History.INFO, logMessage);
                        setInputDataset(placeholderName, null);
                        if (moduleTypeVersionDataset.getMode().equals(ModuleTypeVersionDataset.INOUT)) {
                            setOutputDataset(placeholderName, null); // TODO verify this makes sense
                        }
                    } else {
                        throw new EmfException(String.format("Required %s dataset placeholder %s is not bound to a dataset",
                                                             moduleTypeVersionDataset.getMode(), placeholderName));
                    }
                } else {
                    dataset = datasetDAO.getDataset(entityManagerFactory.createEntityManager(), moduleDataset.getDatasetId());
                    versionNumber = moduleDataset.getVersion();
                    
                    InternalSource internalSource = getInternalSource(dataset);
    
                    logMessage = String.format("%s %s dataset placeholder %s:\n  * dataset type: '%s'\n  * dataset name: '%s'\n  * table name: '%s'\n  * version: %d",
                                               isOptional ? "Optional " : "", moduleTypeVersionDataset.getMode(), placeholderName,
                                               internalSource.getType(), dataset.getName(), internalSource.getTable(), versionNumber);
                    history.addLogMessage(History.INFO, logMessage);
                    
                    setInputDataset(placeholderName, new DatasetVersion(dataset, versionNumber));
                    if (moduleTypeVersionDataset.getMode().equals(ModuleTypeVersionDataset.INOUT)) {
                        setOutputDataset(placeholderName, new DatasetVersion(dataset, versionNumber));
                    }
                }
            }
            
            HistoryDataset historyDataset = new HistoryDataset();
            historyDataset.setPlaceholderName(placeholderName);
            historyDataset.setDatasetId((dataset == null) ? null : dataset.getId());
            historyDataset.setVersion(versionNumber);
            historyDataset.setHistory(history);
            historyDatasets.put(placeholderName, historyDataset);
        }
        history.setHistoryDatasets(historyDatasets);
    }

    protected static InternalSource getInternalSource(EmfDataset dataset) throws EmfException {
        InternalSource[] internalSources = dataset.getInternalSources();
        if (internalSources.length != 1) {
            String errorMessage = String.format("Internal error: dataset '%s' has %d internal sources (expected one).",
                                                dataset.getName(), internalSources.length);
            throw new EmfException(errorMessage);
        }
        return internalSources[0];
    }

    protected void execute() {
        // implemented in derived classes
    }

    public void run() throws EmfException {
        getModuleRunnerContext().start();
        getModuleRunnerContext().createHistory();
        execute();
        getModuleRunnerContext().stop();
    }

    public String getFinalStatusMessage() {
        return finalStatusMessage;
    }

    public String setFinalStatusMessage(String finalStatusMessage) {
        return this.finalStatusMessage = finalStatusMessage;
    }

    ModuleRunnerContext getModuleRunnerContext() {
        return moduleRunnerContext;
    }
    
    // public accessors
    
    public ModuleRunnerTask getTask() {
        return moduleRunnerContext.getTask();
    }

    public User getUser() {
        return moduleRunnerContext.getUser();
    }

    public DatasetDAO getDatasetDAO() {
        return moduleRunnerContext.getDatasetDAO();
    }
    
    public StatusDAO getStatusDAO() {
        return moduleRunnerContext.getStatusDAO();
    }

    public ModulesDAO getModulesDAO() {
        return moduleRunnerContext.getModulesDAO();
    }
    
    public EntityManagerFactory getEntityManagerFactory() {
        return moduleRunnerContext.getEntityManagerFactory();
    }

    public EntityManager getEntityManager() {
        return moduleRunnerContext.getEntityManager();
    }

    public DbServerFactory getDbServerFactory() {
        return moduleRunnerContext.getDbServerFactory();
    }

    public DbServer getDbServer() {
        return moduleRunnerContext.getDbServer();
    }

    public Connection getConnection() {
        return moduleRunnerContext.getConnection();
    }

    public Datasource getDatasource() {
        return moduleRunnerContext.getDatasource();
    }
    
    public boolean getVerboseStatusLogging() {
        return moduleRunnerContext.getVerboseStatusLogging();
    }
    
    public Module getModule() {
        return moduleRunnerContext.getModule();
    }

    // history accessors
    
    public String getStatus() {
        return getHistory().getStatus();
    }

    public void setStatus(String status) {
        getHistory().setStatus(status);
    }

    public String getResult() {
        return getHistory().getResult();
    }

    public void setResult(String result) {
        getHistory().setResult(result);
    }

    // other accessors

    public ModuleTypeVersion getModuleTypeVersion() {
        return getModule().getModuleTypeVersion();
    }

    public History getHistory() {
        return moduleRunnerContext.getHistory();
    }

    public Date getStartDate() {
        return moduleRunnerContext.getStartDate();
    }

    public String getTimeStamp() {
        return moduleRunnerContext.getTimeStamp();
    }

    public String getUserTimeStamp() {
        return moduleRunnerContext.getUserTimeStamp();
    }

    public String getTempUserPassword() {
        return moduleRunnerContext.getTempUserPassword();
    }
    
    //-----------------------------------------------------------------
    
    public Map<String, DatasetVersion> getInputDatasets() {
        return inputDatasets;
    }

    public DatasetVersion getInputDataset(String placeholderName) {
        return this.inputDatasets.get(placeholderName);
    }

    public void setInputDatasets(Map<String, DatasetVersion> inputDatasets) {
        this.inputDatasets = inputDatasets;
    }

    public void setInputDataset(String placeholderName, DatasetVersion inputDataset) {
        this.inputDatasets.put(placeholderName, inputDataset);
    }

    public Map<String, DatasetVersion> getOutputDatasets() {
        return outputDatasets;
    }

    public DatasetVersion getOutputDataset(String placeholderName) {
        return this.outputDatasets.get(placeholderName);
    }

    public void setOutputDatasets(Map<String, DatasetVersion> outputDatasets) {
        this.outputDatasets = outputDatasets;
    }

    public void setOutputDataset(String placeholderName, DatasetVersion outputDataset) {
        this.outputDatasets.put(placeholderName, outputDataset);
    }

    //-----------------------------------------------------------------
    
    public Map<String, String> getInputParameters() {
        return inputParameters;
    }

    public String getInputParameter(String parameterName) {
        return this.inputParameters.get(parameterName);
    }

    public void setInputParameters(Map<String, String> inputParameters) {
        this.inputParameters = inputParameters;
    }

    public void setInputParameter(String parameterName, String value) {
        this.inputParameters.put(parameterName, value);
    }

    public Map<String, String> getOutputParameters() {
        return outputParameters;
    }

    public String getOutputParameter(String parameterName) {
        return this.outputParameters.get(parameterName);
    }

    public void setOutputParameters(Map<String, String> outputParameters) {
        this.outputParameters = outputParameters;
    }

    public void setOutputParameter(String parameterName, String value) {
        this.outputParameters.put(parameterName, value);
    }

    //-----------------------------------------------------------------
    
    public String getPath() {
        return "";
    }

    public String getPathNames() {
        return "";
    }

    public final String getPath(String parameterOrPlaceholderName) {
        String path = getPath();
        if (path.isEmpty())
            return parameterOrPlaceholderName;
        return path + "/" + parameterOrPlaceholderName;
    }

    public final String getPathNames(String parameterOrPlaceholderName) {
        String pathNames = getPathNames();
        if (pathNames.isEmpty())
            return parameterOrPlaceholderName;
        return pathNames + " / " + parameterOrPlaceholderName;
    }
    
    //-----------------------------------------------------------------
    
    protected static Version getVersion(EmfDataset dataset, int versionNumber, EntityManager entityManager) {
        Versions versions = new Versions();
        return versions.get(dataset.getId(), versionNumber, entityManager);
    }

    protected static int updateVersion(EmfDataset dataset, Version version, DbServer dbServer, EntityManager entityManager, DatasetDAO datasetDAO, User user) throws Exception {
        version = datasetDAO.obtainLockOnVersion(user, version.getId(), entityManager);
        int recordCount = (int)datasetDAO.getDatasetRecordsNumber(dbServer, entityManager, dataset, version);
        version.setNumberRecords(recordCount);
        version.setCreator(user);
        datasetDAO.updateVersionNReleaseLock(version, entityManager);
        return recordCount;
    }

    protected static String getNewDatasetName(String datasetNamePattern, User user, Date date, History history) throws EmfException {
        String datasetName = replaceGlobalPlaceholders(datasetNamePattern, user, date, history);
        assertNoPlaceholders(datasetName, "$");
        
        // replace any non-OUT parameters with the input value
        for (ModuleParameter parameter : history.getModule().getModuleParameters().values()) {
            ModuleTypeVersionParameter mtvParam = parameter.getModuleTypeVersionParameter();
            if (!mtvParam.isModeOUT()) {
                datasetName = Pattern.compile("#\\{\\s*" + mtvParam.getParameterName() + "\\s*\\}", Pattern.CASE_INSENSITIVE)
                                     .matcher(datasetName).replaceAll(parameter.hasValue() ? parameter.getValue() : "");
            }
        }
        return datasetName;
    }

    // throws error if dataset cannot be replaced
    // returns if the dataset can be replaced
    // warnings may contain warning message(s) on return 
    protected static void checkDatasetReplacementRules(final StringBuilder warnings, ModuleRunnerContext moduleRunnerContext, EmfDataset dataset, Module module) throws EmfException {
        warnings.setLength(0);
        
        DatasetDAO datasetDAO = moduleRunnerContext.getDatasetDAO();
        ModulesDAO modulesDAO = moduleRunnerContext.getModulesDAO();
        DbServer dbServer = moduleRunnerContext.getDbServer();
        User user = moduleRunnerContext.getUser();
        EntityManager entityManager = moduleRunnerContext.getEntityManager();
        
        // 5. if any dataset version is final
        // ==> error: cannot delete or replace
        Versions versions = new Versions();
        Version[] datasetVersions = versions.get(dataset.getId(), entityManager);
        for (Version version : datasetVersions) {
            if (version.isFinalVersion()) {
                String errorMessage = String.format("Cannot delete or replace dataset '%s': the dataset version %d is final.",
                                                    dataset.getName(), version.getVersion());
                throw new EmfException(errorMessage);
            }
        }

        // 1.d. if the current user doesn't own the dataset and he is not an admin user
        // ==> error: cannot delete or replace
        if (!dataset.getCreator().equals(user.getUsername()) && !user.isAdmin()) {
            String errorMessage = String.format("Cannot delete or replace dataset '%s': the dataset was created by %s (%s) and the current user %s (%s) is not an administrator.",
                                                dataset.getName(), dataset.getCreator(), dataset.getCreatorFullName(), user.getUsername(), user.getName());
            throw new EmfException(errorMessage);
        }
        
        // check if dataset is used
        boolean isUsedByCases = datasetDAO.isUsedByCases(entityManager, dataset);
        boolean isUsedByControlStrategies = datasetDAO.isUsedByControlStrategies(entityManager, dataset);
        boolean isUsedByControlPrograms = datasetDAO.isUsedByControlPrograms(dataset.getId(), entityManager);
        boolean isUsedByFast;
        try {
            isUsedByFast = datasetDAO.isUsedByFast(dataset.getId(), user, dbServer, entityManager);
        } catch (Exception e) {
            e.printStackTrace();
            throw new EmfException("Error checking if dataset '" + dataset.getName() + "' is used by FAST: " + e.getMessage());
        }
        boolean isUsedByTemporalAllocations;
        try {
            isUsedByTemporalAllocations = datasetDAO.isUsedByTemporalAllocations(dataset.getId(), user, entityManager);
        } catch (Exception e) {
            e.printStackTrace();
            throw new EmfException("Error checking if dataset '" + dataset.getName() + "' is used by Temporal Allocations: " + e.getMessage());
        }
        boolean isUsedByNonModuleComponents = isUsedByCases || isUsedByControlStrategies || isUsedByControlPrograms || isUsedByFast || isUsedByTemporalAllocations;
        List<Integer> consumerModuleIds;
        try {
            consumerModuleIds = datasetDAO.getModulesUsingDataset(dataset.getId());
        } catch (Exception e) {
            e.printStackTrace();
            throw new EmfException("Error checking if dataset '" + dataset.getName() + "' is used by modules: " + e.getMessage());
        }
        consumerModuleIds.remove(Integer.valueOf(module.getId()));
        boolean isUsedByOtherModules = (consumerModuleIds.size() > 0);
        
        // 2. dataset is used by non-module components
        // ==> error: cannot delete or replace
        if (isUsedByNonModuleComponents) {
            StringBuilder list = new StringBuilder(); 
            if (isUsedByCases) list.append("Cases");
            if (isUsedByControlStrategies) {
                if (list.length() > 0) list.append(", ");
                list.append("Control Strategies");
            }
            if (isUsedByControlPrograms) {
                if (list.length() > 0) list.append(", and ");
                list.append("Control Programs");
            }
            if (isUsedByFast) {
                if (list.length() > 0) list.append(", and ");
                list.append("Fast");
            }
            if (isUsedByTemporalAllocations) {
                if (list.length() > 0) list.append(", and ");
                list.append("Temporal Allocations");
            }
            String errorMessage = String.format("Cannot delete or replace dataset '%s': the dataset is used by %s.", dataset.getName(), list.toString());
            throw new EmfException(errorMessage);
        }
        
        // 3. if dataset is used only by Modules
        // ==> can delete and replace, warning listing all modules that use that dataset
        if (isUsedByOtherModules) {
            StringBuilder consumerModuleNames = new StringBuilder(); 
            for(int consumerModuleId : consumerModuleIds) {
                Module consumerModule = modulesDAO.getModule(consumerModuleId, entityManager);
                if (consumerModuleNames.length() > 0) {
                    consumerModuleNames.append("\n");
                }
                consumerModuleNames.append(consumerModule.getName());
            }
            warnings.append(String.format("Dataset '%s' is used by the following module(s):%s\n",
                                          dataset.getName(), consumerModuleNames.toString()));
        }

        // 1. if all dataset versions are non-final and
        //    if dataset is not used by any component of the EMF (Cases, CoST, Modules, etc)
        // ==> can delete and replace, warn if the dataset has more then one version
        if (datasetVersions.length > 1) {
            warnings.append(String.format("Dataset '%s' has %d versions. Version 1 will be replaced and all other versions will be deleted.\n",
                                          dataset.getName(), datasetVersions.length));
        }
    }
    
    protected static String replaceGlobalPlaceholders(String text, User user, Date date, History history) {
        Module module = history.getModule();
        
        // replace global placeholders in the algorithm
        String startPattern = "\\$\\{\\s*";
        String separatorPattern = "\\s*\\.\\s*";
        String endPattern = "\\s*\\}";

        // Important: keep the list of valid placeholders in sync with
        //            gov.epa.emissions.framework.services.module.ModuleTypeVersion

        text = Pattern.compile(startPattern + "user" + separatorPattern + "full_name" + endPattern, Pattern.CASE_INSENSITIVE)
                      .matcher(text).replaceAll(user.getName());

        text = Pattern.compile(startPattern + "user" + separatorPattern + "id" + endPattern, Pattern.CASE_INSENSITIVE)
                      .matcher(text).replaceAll(user.getId() + "");

        text = Pattern.compile(startPattern + "user" + separatorPattern + "account_name" + endPattern, Pattern.CASE_INSENSITIVE)
                      .matcher(text).replaceAll(user.getUsername());

        text = Pattern.compile(startPattern + "module" + separatorPattern + "name" + endPattern, Pattern.CASE_INSENSITIVE)
                      .matcher(text).replaceAll(module.getName());

        text = Pattern.compile(startPattern + "module" + separatorPattern + "id" + endPattern, Pattern.CASE_INSENSITIVE)
                      .matcher(text).replaceAll(module.getId() + "");

        text = Pattern.compile(startPattern + "module" + separatorPattern + "final" + endPattern, Pattern.CASE_INSENSITIVE)
                      .matcher(text).replaceAll(module.getIsFinal() ? "Final" : "");

        text = Pattern.compile(startPattern + "module" + separatorPattern + "project_name" + endPattern, Pattern.CASE_INSENSITIVE)
                      .matcher(text).replaceAll((module.getProject() == null) ? "" : module.getProject().getName());

        text = Pattern.compile(startPattern + "run" + separatorPattern + "id" + endPattern, Pattern.CASE_INSENSITIVE)
                      .matcher(text).replaceAll(history.getRunId() + "");

        text = Pattern.compile(startPattern + "run" + separatorPattern + "date" + endPattern, Pattern.CASE_INSENSITIVE)
                      .matcher(text).replaceAll(CustomDateFormat.format_MM_DD_YYYY(date));

        text = Pattern.compile(startPattern + "run" + separatorPattern + "time" + endPattern, Pattern.CASE_INSENSITIVE)
                      .matcher(text).replaceAll(CustomDateFormat.format_HHmmssSSS(date));

        return text;
    }

    protected static String replaceDatasetPlaceholders(String algorithm, Module module, ModuleTypeVersionDataset moduleTypeVersionDataset,
                                                       EmfDataset dataset, int version, String viewName) throws EmfException {
        String result = algorithm;
        String startPattern = "\\$\\{\\s*" + moduleTypeVersionDataset.getPlaceholderName();
        String separatorPattern = "\\s*\\.\\s*";
        String endPattern = "\\s*\\}";

        // Important: keep the list of valid placeholders in sync with
        //            gov.epa.emissions.framework.services.module.ModuleTypeVersion

        result = Pattern.compile(startPattern + separatorPattern + "mode" + endPattern, Pattern.CASE_INSENSITIVE)
                        .matcher(result).replaceAll(moduleTypeVersionDataset.getMode());

        if (moduleTypeVersionDataset.getMode().equals(ModuleTypeVersionDataset.OUT)) {
            result = Pattern.compile(startPattern + separatorPattern + "output_method" + endPattern, Pattern.CASE_INSENSITIVE)
                            .matcher(result).replaceAll("NEW"); // internal dataset output method is NEW (with REPLACE if exists)
        }

        String is_optional = "\\/\\* " + moduleTypeVersionDataset.getPlaceholderName() + "\\.is_optional \\*\\/ " +
                             (moduleTypeVersionDataset.getIsOptional() ? "TRUE" : "FALSE");
        
        result = Pattern.compile(startPattern + separatorPattern + "is_optional" + endPattern, Pattern.CASE_INSENSITIVE)
                        .matcher(result).replaceAll(is_optional);

        String is_set = "\\/\\* " + moduleTypeVersionDataset.getPlaceholderName() + "\\.is_set \\*\\/ ";

        if (viewName == null) { // should never happen
            // new simplified syntax
            result = Pattern.compile(startPattern + endPattern, Pattern.CASE_INSENSITIVE)
                            .matcher(result).replaceAll("NULL");
    
            // old syntax
            result = Pattern.compile(startPattern + separatorPattern + "table_name" + endPattern, Pattern.CASE_INSENSITIVE)
                            .matcher(result).replaceAll("NULL");
    
            result = Pattern.compile(startPattern + separatorPattern + "view" + endPattern, Pattern.CASE_INSENSITIVE)
                            .matcher(result).replaceAll("NULL");
        } else {
            // new simplified syntax
            result = Pattern.compile(startPattern + endPattern, Pattern.CASE_INSENSITIVE)
                            .matcher(result).replaceAll(viewName);
    
            // old syntax
            result = Pattern.compile(startPattern + separatorPattern + "table_name" + endPattern, Pattern.CASE_INSENSITIVE)
                            .matcher(result).replaceAll(viewName);
    
            result = Pattern.compile(startPattern + separatorPattern + "view" + endPattern, Pattern.CASE_INSENSITIVE)
                            .matcher(result).replaceAll(viewName);
        }
        
        if (dataset == null) {
            result = Pattern.compile(startPattern + separatorPattern + "is_set" + endPattern, Pattern.CASE_INSENSITIVE)
                            .matcher(result).replaceAll(is_set + "FALSE");
            
            result = Pattern.compile(startPattern + separatorPattern + "dataset_name" + endPattern, Pattern.CASE_INSENSITIVE)
                            .matcher(result).replaceAll("NULL");
    
            result = Pattern.compile(startPattern + separatorPattern + "dataset_id" + endPattern, Pattern.CASE_INSENSITIVE)
                            .matcher(result).replaceAll("NULL");
    
            result = Pattern.compile(startPattern + separatorPattern + "version" + endPattern, Pattern.CASE_INSENSITIVE)
                            .matcher(result).replaceAll("NULL");
        } else {
            InternalSource[] internalSources = dataset.getInternalSources();
            if (internalSources.length == 0) {
                throw new EmfException("Can't handle datasets with no internal sources (module '" + module.getName() + "', dataset '" + dataset.getName() + "').");
            } else if (internalSources.length > 1) {
                throw new EmfException("Can't handle datasets with multiple internal sources (module '" + module.getName() + "', dataset '" + dataset.getName() + "').");
            }
    
            result = Pattern.compile(startPattern + separatorPattern + "is_set" + endPattern, Pattern.CASE_INSENSITIVE)
                            .matcher(result).replaceAll(is_set + "TRUE");

            result = Pattern.compile(startPattern + separatorPattern + "dataset_name" + endPattern, Pattern.CASE_INSENSITIVE)
                            .matcher(result).replaceAll(dataset.getName());
    
            result = Pattern.compile(startPattern + separatorPattern + "dataset_id" + endPattern, Pattern.CASE_INSENSITIVE)
                            .matcher(result).replaceAll(dataset.getId() + "");
    
            result = Pattern.compile(startPattern + separatorPattern + "version" + endPattern, Pattern.CASE_INSENSITIVE)
                            .matcher(result).replaceAll(version + "");
        }
        
        return result;
    }

    protected static String replaceDatasetPlaceholders(String algorithm, ModuleDataset moduleDataset,
                                                       EmfDataset dataset, int version, String viewName) throws EmfException {
        String result = algorithm;
        
        String startPattern = "\\$\\{\\s*" + moduleDataset.getPlaceholderName();
        String separatorPattern = "\\s*\\.\\s*";
        String endPattern = "\\s*\\}";

        ModuleTypeVersionDataset moduleTypeVersionDataset = moduleDataset.getModuleTypeVersionDataset();
        
        if (moduleTypeVersionDataset.getMode().equals(ModuleTypeVersionDataset.OUT)) {
            result = Pattern.compile(startPattern + separatorPattern + "output_method" + endPattern, Pattern.CASE_INSENSITIVE)
                            .matcher(result).replaceAll(moduleDataset.getOutputMethod());
        }

        result = replaceDatasetPlaceholders(algorithm, moduleDataset.getModule(), moduleTypeVersionDataset, dataset, version, viewName);

        return result;
    }

    protected static String replaceParameterPlaceholders(String algorithm, ModuleParameter moduleParameter, String timeStamp) {
        return replaceParameterPlaceholders(algorithm, moduleParameter.getModuleTypeVersionParameter(), moduleParameter.getValue(), timeStamp);
    }

    protected static String replaceParameterPlaceholders(String algorithm, ModuleTypeVersionParameter moduleTypeVersionParameter, String parameterInputValue, String timeStamp) {
        String result = algorithm;
        String parameterName = moduleTypeVersionParameter.getParameterName();
        String parameterTimeStamp = parameterName + "_" + timeStamp; 
        String startPattern = "\\#\\{\\s*" + parameterName;
        String separatorPattern = "\\s*\\.\\s*";
        String endPattern = "\\s*\\}";

        // Important: keep the list of valid placeholders in sync with
        //            gov.epa.emissions.framework.services.module.ModuleTypeVersion

        result = Pattern.compile(startPattern + endPattern, Pattern.CASE_INSENSITIVE)
                        .matcher(result).replaceAll(parameterTimeStamp);

        result = Pattern.compile(startPattern + separatorPattern + "sql_type" + endPattern, Pattern.CASE_INSENSITIVE)
                        .matcher(result).replaceAll(moduleTypeVersionParameter.getSqlParameterType());

        result = Pattern.compile(startPattern + separatorPattern + "mode" + endPattern, Pattern.CASE_INSENSITIVE)
                        .matcher(result).replaceAll(moduleTypeVersionParameter.getMode());

        String is_optional = "\\/\\* " + moduleTypeVersionParameter.getParameterName() + "\\.is_optional \\*\\/ " +
                             (moduleTypeVersionParameter.getIsOptional() ? "TRUE" : "FALSE");

        result = Pattern.compile(startPattern + separatorPattern + "is_optional" + endPattern, Pattern.CASE_INSENSITIVE)
                        .matcher(result).replaceAll(is_optional);

        // TODO for string module parameters: distinguish between "" (empty string) and NULL (not set)
        boolean hasValue = (parameterInputValue != null) && (parameterInputValue.trim().length() > 0);
        
        String is_set = "\\/\\* " + moduleTypeVersionParameter.getParameterName() + "\\.is_set \\*\\/ " +
                        (hasValue ? "TRUE" : "FALSE");

        result = Pattern.compile(startPattern + separatorPattern + "is_set" + endPattern, Pattern.CASE_INSENSITIVE)
                        .matcher(result).replaceAll(is_set);

        if (!moduleTypeVersionParameter.getMode().equals(ModuleTypeVersionDataset.OUT)) {
            result = Pattern.compile(startPattern + separatorPattern + "input_value" + endPattern, Pattern.CASE_INSENSITIVE)
                            .matcher(result).replaceAll(hasValue ? parameterInputValue : "NULL");
        }

        return result;
    }

    protected static void assertNoPlaceholders(String text, String placeholderMark) throws EmfException {
        String startPattern = "\\" + placeholderMark + "\\{\\s*";
        String endPattern = "\\s*\\}";

        Matcher matcher = Pattern.compile(startPattern + ".*?" + endPattern, Pattern.CASE_INSENSITIVE).matcher(text);
        while (matcher.find()) {
            int pos = matcher.start();
            if (isSqlComment(text, pos))
                continue;
            String match = matcher.group();
            int lineNumber = findLineNumber(text, pos);
            String message = String.format("Unrecognized placeholder %s at line %d location %d.", match, pos, lineNumber);
            throw new EmfException(message); 
        }
    }
    
    protected static boolean isSqlComment(String text, int position) {
        return ModuleTypeVersion.isSqlComment(text, position);
    }

    // returns line number for character position (first line number is 1)
    protected static int findLineNumber(String text, int characterPosition) {
        String subtext = text.substring(0, characterPosition);
        return subtext.length() - subtext.replace("\n", "").length() + 1;
    }

    protected static String getVersionWhereFilter(Connection connection, int datasetId, int version, String table_alias) throws EmfException {
        Statement statement = null;
        try {
            statement = connection.createStatement();
            String query = String.format("SELECT public.build_version_where_filter(%d, %d, '%s')", datasetId, version, table_alias);
            if (statement.execute(query)) {
                // get the return value
                ResultSet resultSet = statement.getResultSet();
                while(resultSet.next()) {
                    return resultSet.getString(1);
                }
            }
        } catch (SQLException e) {
            throw new EmfException(String.format("Failed to get the version where filter for dataset_id %d version %d: %s", datasetId, version, e.getMessage()));
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
        throw new EmfException(String.format("Failed to get the version where filter for dataset_id %d version %d", datasetId, version));
    }
    
    protected static String formatColumn(Column column, String indent, String comma) { 
        StringBuilder colDesc = new StringBuilder();
        colDesc.append(indent);
        colDesc.append(column.getName());
        colDesc.append(" ");
        colDesc.append(column.getSqlType());
        if (column.getConstraints() != null && column.getConstraints().trim().length() > 0) {
            colDesc.append(" ");
            colDesc.append(column.getConstraints());
        }
        if (column.getDefaultValue() != null && column.getDefaultValue().trim().length() > 0) {
            colDesc.append(" DEFAULT ");
            colDesc.append(column.getDefaultValue());
        }
        colDesc.append(comma);
        if (column.getDescription() != null && column.getDescription().trim().length() > 0) {
            colDesc.append(" -- ");
            colDesc.append(column.getDescription());
        }
        return colDesc.toString();
    }

    protected static void createView(final StringBuilder viewName, final StringBuilder viewDefinition,
                                     Connection connection, Module module, ModuleTypeVersionDataset moduleTypeVersionDataset, DbServer dbServer,
                                     EmfDataset emfDataset, int version) throws EmfException {
        viewName.setLength(0);
        viewDefinition.setLength(0);
        
        String mode = moduleTypeVersionDataset.getMode();
        String modeAbbr = mode.toLowerCase().substring(0, 1);
        if (mode.equals(ModuleTypeVersionDataset.INOUT)) {
            modeAbbr = "io";
        }
        
        TableFormat tableFormat = getTableFormat(moduleTypeVersionDataset, dbServer);
        Column[] columns = tableFormat.cols();
        
        StringBuilder colNames = new StringBuilder();
        StringBuilder newColNames = new StringBuilder();
        List<String> colDesc = new ArrayList<String>();
        List<String> colDefs = new ArrayList<String>();
        String datasetIdColName = "dataset_id";
        String versionColName = "version";
        for(int i = 0; i < columns.length; i++) {
            Column column = columns[i];
            String colName = column.getName();
            if (colName.toLowerCase().equals("record_id"))
                continue;
            if (colName.toLowerCase().equals("dataset_id")) {
                datasetIdColName = colName;
                continue;
            }
            if (colName.toLowerCase().equals("version")) {
                versionColName = colName;
                continue;
            }
            if (colName.toLowerCase().equals("delete_versions"))
                continue;
            
            if (colNames.length() > 0)
                colNames.append(", ");
            colNames.append(colName);
            if (newColNames.length() > 0)
                newColNames.append(", ");
            newColNames.append("NEW." + colName);
            
            colDesc.add(formatColumn(column, "    -- ", ""));
            if (i == (columns.length - 1)) {
                colDefs.add(formatColumn(column, "        ", ""));
            } else {
                colDefs.add(formatColumn(column, "        ", ", "));
            }
        }
        
        if (emfDataset == null) {
            viewName.append(moduleTypeVersionDataset.getPlaceholderName() + "_" + modeAbbr + "t");
            
            viewDefinition.append("    CREATE TEMP TABLE " + viewName.toString() + "\n");
            viewDefinition.append("    (\n");
            for (String colDef : colDefs) {
                viewDefinition.append(colDef + "\n");
            }
            viewDefinition.append("    );\n\n");
        } else {
            viewName.append(moduleTypeVersionDataset.getPlaceholderName() + "_" + modeAbbr + "v");
            
            int datasetId = emfDataset.getId();
            
            InternalSource[] internalSources = emfDataset.getInternalSources();
            if (internalSources.length == 0) {
                throw new EmfException("Can't handle datasets with no internal sources (module '" + module.getName() + "', dataset '" + emfDataset.getName() + "').");
            } else if (internalSources.length > 1) {
                throw new EmfException("Can't handle datasets with multiple internal sources (module '" + module.getName() + "', dataset '" + emfDataset.getName() + "').");
            }
            
            // FIXME hard-coded schema name
            String tableName = EmfDbServer.EMF_EMISSIONS_SCHEMA + "." + internalSources[0].getTable();
    
            String versionWhereFilter = getVersionWhereFilter(connection, datasetId, version, "ds");

            for (String colDescVal : colDesc) {
                viewDefinition.append(colDescVal + "\n");
            }
            viewDefinition.append(String.format("    CREATE TEMP VIEW %s AS\n" +
                                                "        SELECT %s\n" +
                                                "        FROM %s ds\n" +
                                                "        WHERE %s;\n\n",
                                                viewName.toString(),
                                                colNames.toString(),
                                                tableName,
                                                versionWhereFilter));
            
            if (!mode.equals(ModuleTypeVersionDataset.IN)) {
                viewDefinition.append(String.format("    CREATE RULE %si AS ON INSERT TO %s\n" +
                                                    "    DO INSTEAD\n" +
                                                    "    INSERT INTO %s\n" +
                                                    "        (%s, %s, %s)\n" +
                                                    "    VALUES\n" +
                                                    "        (%d, %d, %s)\n" +
                                                    "    RETURNING\n" +
                                                    "        %s;\n\n",
                                                    viewName.toString(), viewName.toString(),
                                                    tableName,
                                                    datasetIdColName, versionColName, colNames.toString(),
                                                    datasetId, version, newColNames.toString(),
                                                    colNames.toString()));
            }
        }
    }

    protected static String getGrantPermissionsScript(String tempUserName, List<String> outputDatasetTables) {
        String permissionsScript =
            "DO $$\n" +
            "BEGIN\n" +
            "    GRANT USAGE ON SCHEMA cases, emf, emissions, fast, modules, reference, sms TO ${temp_user};\n" +
            "    GRANT SELECT, REFERENCES ON ALL TABLES IN SCHEMA cases, emf, emissions, fast, modules, reference, sms TO ${temp_user};\n" +
            "    GRANT USAGE ON ALL SEQUENCES IN SCHEMA cases, emf, emissions, fast, modules, reference, sms TO ${temp_user};\n" +
            "    GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA cases, emf, emissions, fast, modules, reference, sms TO ${temp_user};\n" +
            "\n" +
            "    IF EXISTS(SELECT 1 FROM information_schema.schemata WHERE schema_name = 'tiger_data')\n" +
            "    THEN\n" +
            "        GRANT USAGE ON SCHEMA tiger, tiger_data TO ${temp_user};\n" +
            "        GRANT SELECT, REFERENCES ON ALL TABLES IN SCHEMA tiger, tiger_data TO ${temp_user};\n" +
            "        GRANT USAGE ON ALL SEQUENCES IN SCHEMA tiger, tiger_data TO ${temp_user};\n" +
            "        GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA tiger, tiger_data TO ${temp_user};\n" +
            "    END IF;\n" +
            "END $$;\n\n";
        
        // TODO grant USAGE permissions for all procedural languages, domains, and types

        for(String outputDatasetTable : outputDatasetTables)
            permissionsScript += "GRANT INSERT, UPDATE, DELETE, TRUNCATE ON TABLE " + outputDatasetTable + " TO ${temp_user};\n";
        if (!outputDatasetTables.isEmpty())
            permissionsScript += "\n";
        
        permissionsScript = Pattern.compile("\\$\\{temp_user\\}", Pattern.CASE_INSENSITIVE)
                                   .matcher(permissionsScript).replaceAll(tempUserName);
        return permissionsScript;
    }
    
    protected static String getDenyPermissionsScript(String tempUserName, List<String> outputDatasetTables) {
        String permissionsScript = "";
        for(String outputDatasetTable : outputDatasetTables)
            permissionsScript += "REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON TABLE " + outputDatasetTable + " FROM ${temp_user};\n";
        if (!outputDatasetTables.isEmpty())
            permissionsScript += "\n";
        
        permissionsScript = Pattern.compile("\\$\\{temp_user\\}", Pattern.CASE_INSENSITIVE)
                                   .matcher(permissionsScript).replaceAll(tempUserName);
        return permissionsScript;
    }

    protected static String getTempUserSetupScript(String tempUserName, String tempUserPassword) {
        // create new temporary user
        // create new temporary schema with the same name as the temporary user
        // the default search path will be the new temporary schema followed by public
        String setupScript =
            // TODO use encrypted password
            "CREATE USER ${temp_user} WITH CONNECTION LIMIT 1 PASSWORD '${temp_password}';\n" +
            "GRANT CONNECT, TEMPORARY ON DATABASE \"EMF\" TO ${temp_user};\n" +
            "CREATE SCHEMA AUTHORIZATION ${temp_user};\n\n";

        // TODO grant USAGE permissions for all procedural languages, domains, and types

        setupScript = Pattern.compile("\\$\\{temp_user\\}", Pattern.CASE_INSENSITIVE)
                             .matcher(setupScript).replaceAll(tempUserName);

        setupScript = Pattern.compile("\\$\\{temp_password\\}", Pattern.CASE_INSENSITIVE)
                             .matcher(setupScript).replaceAll(tempUserPassword);
        return setupScript;
    }
    
    protected static String getTempUserTeardownScript(String tempUserName) {
        String teardownScript =
            "REINDEX SYSTEM \"EMF\";\n" +
            "\n" +
            "DO $$\n" +
            "BEGIN\n" +
            "    IF EXISTS(SELECT 1 FROM information_schema.schemata WHERE schema_name = '${temp_user}')\n" +
            "    THEN\n" +
            "        DROP OWNED BY ${temp_user} CASCADE;\n" +
            "        DROP USER ${temp_user};\n" +
            "    END IF;\n" +
            "END $$;\n";

        teardownScript = Pattern.compile("\\$\\{temp_user\\}", Pattern.CASE_INSENSITIVE)
                                .matcher(teardownScript).replaceAll(tempUserName);
        return teardownScript;
    }

    protected static Connection getUserConnection(String username, String password) throws InfrastructureException, SQLException { 
        BasicDataSource basicDataSource = (BasicDataSource)new DataSourceFactory().get();
        String jdbcDriverClassName = basicDataSource.getDriverClassName();
        String jdbcURL = basicDataSource.getUrl();

        DriverManager.registerDriver(basicDataSource.getDriver());
        Connection ds = DriverManager.getConnection(jdbcURL + "&ApplicationName=Module%20Runner&logUnclosedConnections=true&loglevel=2", 
                username, 
                password);
        
        return ds;
    }

    protected static String getLineNumberedScript(String script) {
        if (script == null)
            return "";
        
        StringBuilder lineNumberedScript = new StringBuilder();
        int charNumber = 1;
        int lineNumber = 1;
        String[] lines = script.split("\n");
        for(String line : lines) {
            if (lineNumber == 1)
                lineNumberedScript.append("Line  Char  Text\n");
            lineNumberedScript.append(String.format("%4d %5d  %s\n", lineNumber, charNumber, line));
            lineNumber++;
            charNumber += line.length() + 1;
        }
        return lineNumberedScript.toString();
    }
    
    protected void executeTeardownScript(@SuppressWarnings("unused") List<String> outputDatasetTables) throws EmfException {
        Connection connection = moduleRunnerContext.getConnection();
        ModulesDAO modulesDAO = moduleRunnerContext.getModulesDAO();
        EntityManager entityManager = moduleRunnerContext.getEntityManager();
        History history = moduleRunnerContext.getHistory();
        
        String teardownScript = getTempUserTeardownScript(moduleRunnerContext.getUserTimeStamp());

        Statement statement = null;
        try {
            history.setTeardownScript(teardownScript);
            
            history.addLogMessage(History.INFO, "Starting teardown script.");
            
            history = modulesDAO.updateHistory(history, entityManager);
            
            statement = connection.createStatement();
            int count = 3;
            for (int attempt = 0; attempt < count; attempt++) {
                try {
                    statement.execute(teardownScript);
                    break;
                } catch (Exception e) {
                    if (attempt == count - 1) // last attempt?
                        throw e;
                    history.addLogMessage(History.WARNING, TEARDOWN_SCRIPT_ERROR + "\n" + e.getMessage() + "\n\n" +
                                                           getLineNumberedScript(teardownScript) + "\n\nRetrying ...\n\n");
                }
            }
            
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

    protected void transferData(DatasetVersion sourceDatasetVersion, DatasetVersion targetDatasetVersion) throws EmfException, SQLException {
        Connection connection = moduleRunnerContext.getConnection();
        String schema = EmfDbServer.EMF_EMISSIONS_SCHEMA; // FIXME hard-coded schema name

        EmfDataset sourceDataset = sourceDatasetVersion.getDataset();
        EmfDataset targetDataset = targetDatasetVersion.getDataset();
        
        // assert same dataset type
        if (!sourceDataset.getDatasetType().equals(targetDataset.getDatasetType())) {
            throw new EmfException("Internal error: \"" + sourceDataset.getName() + "\" and \"" + targetDataset.getName() + "\" have different dataset types.");
        }

        // assert version 0 for the target
        if (targetDatasetVersion.getVersion() != 0) {
            throw new EmfException("Internal error: unexpected version (" + targetDatasetVersion.getVersion() + ") for target dataset \"" + targetDataset.getName() + "\": expected version 0");
        }

        // TODO assert 0 records and only one Version for the target dataset
        
        Statement statement = null;
        try {
            InternalSource internalSource = getInternalSource(sourceDataset);
            InternalSource internalTarget = getInternalSource(targetDataset);
            
            String sourceTable = internalSource.getTable();
            String targetTable = internalTarget.getTable();
            
            StringBuilder colNames = new StringBuilder();
            String datasetIdColName = "dataset_id";
            String versionColName = "version";
            for(String col : internalSource.getCols()) {
                if (col.toLowerCase().equals("record_id"))
                    continue;
                if (col.toLowerCase().equals("dataset_id")) {
                    datasetIdColName = col;
                    continue;
                }
                if (col.toLowerCase().equals("version")) {
                    versionColName = col;
                    continue;
                }
                if (col.toLowerCase().equals("delete_versions"))
                    continue;
                if (colNames.length() > 0)
                    colNames.append(", ");
                colNames.append(col);
            }
            
            String versionWhereFilter = getVersionWhereFilter(connection, sourceDataset.getId(), sourceDatasetVersion.getVersion(), "ds");
            
            String query = String.format("INSERT INTO %s.%s\n" +
                                         "    (%s, %s, %s)\n" +
                                         "SELECT %d, 0, %s\n" +
                                         "FROM %s.%s ds\n" +
                                         "WHERE %s",
                                         schema, targetTable, datasetIdColName, versionColName, colNames.toString(),
                                         targetDataset.getId(), colNames.toString(),
                                         schema, sourceTable,
                                         versionWhereFilter);
            statement = connection.createStatement();
            statement.execute(query);
            
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
    
    protected final void deleteDatasets(EmfDataset[] datasets) throws EmfException {
        DbServer dbServer = getDbServer();
        DatasetDAO datasetDAO = getDatasetDAO();
        EntityManager entityManager = getEntityManager();
        datasetDAO.deleteDatasets(datasets, dbServer, entityManager);
    }
    
    protected void collectTemporaryDatasets(Map<String, EmfDataset> datasets) {
        for(String placeholderName : outputDatasets.keySet()) {
            DatasetVersion datasetVersion = outputDatasets.get(placeholderName);
            if (datasetVersion == null)
                continue;
            if (!datasetVersion.isKeep() && datasetVersion.getDataset() != null) {
                datasets.put(getPathNames(placeholderName), datasetVersion.getDataset());
            }
        }
    }

    protected static boolean wasDatasetCreatedByModule(EmfDataset dataset, Module module, String placeholderPathNames) {
        KeyVal[] keyVals = dataset.getKeyVals();
        int checkCount = 0;
        for(KeyVal keyVal : keyVals) {
//            if (keyVal.getKwname().equals("MODULE_NAME") && keyVal.getValue().equals(module.getName()))
//                checkCount++;
            if (keyVal.getKwname().equals("MODULE_ID") && keyVal.getValue().equals(module.getId() + ""))
                checkCount++;
            if (keyVal.getKwname().equals("MODULE_PLACEHOLDER") && keyVal.getValue().equals(placeholderPathNames))
                checkCount++;
        }
        return (checkCount == 2);
    }
}
