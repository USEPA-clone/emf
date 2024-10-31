package gov.epa.emissions.framework.services.module;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import javax.persistence.EntityManager;

import gov.epa.emissions.commons.data.InternalSource;
import gov.epa.emissions.commons.db.DbServer;
import gov.epa.emissions.commons.db.version.Version;
import gov.epa.emissions.commons.security.User;
import gov.epa.emissions.framework.services.EmfDbServer;
import gov.epa.emissions.framework.services.EmfException;
import gov.epa.emissions.framework.services.data.DatasetDAO;
import gov.epa.emissions.framework.services.data.EmfDataset;

class SimpleSubmoduleRunner extends SubmoduleRunner {

    public SimpleSubmoduleRunner(ModuleRunnerContext moduleRunnerContext, ModuleRunner parentModuleRunner, ModuleTypeVersionSubmodule moduleTypeVersionSubmodule) throws EmfException {
        super(moduleRunnerContext, parentModuleRunner, moduleTypeVersionSubmodule);
        if (moduleTypeVersionSubmodule.getModuleTypeVersion().isComposite()) {
            throw new EmfException("Internal error: \"" + getModule().getName() + " / " + getPathNames() + "\" is a composite submodule");
        }
    }
    
    protected void execute() {
        DbServer dbServer = getDbServer();
        User user = getUser();
        Connection connection = getConnection();
        DatasetDAO datasetDAO = getDatasetDAO();
        ModulesDAO modulesDAO = getModulesDAO();
        EntityManager entityManager = getEntityManager();
        
        Module module = getModule();
        ModuleTypeVersionSubmodule moduleTypeVersionSubmodule = getModuleTypeVersionSubmodule();
        ModuleTypeVersion moduleTypeVersion = moduleTypeVersionSubmodule.getModuleTypeVersion();
        Map<String, ModuleInternalParameter> moduleInternalParameters = module.getModuleInternalParameters();
        History history = getHistory();
        Map<String, HistoryInternalParameter> historyInternalParameters = history.getHistoryInternalParameters();
        HistorySubmodule historySubmodule = getHistorySubmodule();
        Date startDate = getStartDate();
        String timeStamp = getTimeStamp();
        String userTimeStamp = getUserTimeStamp();

        String errorMessage = "";
        
        Statement statement = null;
        
        setFinalStatusMessage("");
        
        List<Version> outputDatasetVersions = new ArrayList<Version>();
        List<String> outputDatasetTables = new ArrayList<String>();
        
        try {
            String algorithm = moduleTypeVersion.getAlgorithm();
            historySubmodule.setUserScript(algorithm);

            createDatasets();
            
            String datasetTablesSchema = EmfDbServer.EMF_EMISSIONS_SCHEMA; // FIXME hard-coded schema name
            
            StringBuilder viewDefinitions = new StringBuilder();
            
            // create views for all datasets
            // replace all dataset placeholders in the algorithm
            for(ModuleTypeVersionDataset moduleTypeVersionDataset : moduleTypeVersion.getModuleTypeVersionDatasets().values()) {
                String placeholderName = moduleTypeVersionDataset.getPlaceholderName();
                StringBuilder viewName = new StringBuilder();
                StringBuilder viewDefinition = new StringBuilder();
                DatasetVersion datasetVersion = null;
                if (moduleTypeVersionDataset.getMode().equals(ModuleTypeVersionDataset.OUT)) {
                    datasetVersion = getOutputDataset(placeholderName);
                } else { // IN or INOUT
                    datasetVersion = getInputDataset(placeholderName);
                }
                if (datasetVersion == null) {
                    createView(viewName, viewDefinition, connection, module, moduleTypeVersionDataset, dbServer, null, 0);
                    viewDefinitions.append(viewDefinition);
                    algorithm = replaceDatasetPlaceholders(algorithm, module, moduleTypeVersionDataset, null, 0, viewName.toString());
                } else {
                    EmfDataset dataset = datasetVersion.getDataset();
                    int versionNumber = datasetVersion.getVersion();
                    InternalSource internalSource = getInternalSource(dataset);
                    if (!moduleTypeVersionDataset.getMode().equals(ModuleTypeVersionDataset.IN)) {
                        Version version = getVersion(dataset, versionNumber, entityManager);
                        outputDatasetVersions.add(version);
                        outputDatasetTables.add(datasetTablesSchema + "." + internalSource.getTable());
                    }
                    createView(viewName, viewDefinition, connection, module, moduleTypeVersionDataset, dbServer, dataset, versionNumber);
                    viewDefinitions.append(viewDefinition);
                    algorithm = replaceDatasetPlaceholders(algorithm, module, moduleTypeVersionDataset, dataset, versionNumber, viewName.toString());
                }
            }
            
            // replace global placeholders in the algorithm
            algorithm = replaceGlobalPlaceholders(algorithm, user, startDate, history);
            
            historySubmodule.setUserScript(algorithm);

            // verify that the algorithm doesn't have any dataset or global placeholders left
            assertNoPlaceholders(algorithm, "$");

            // replace all parameter placeholders
            for(ModuleTypeVersionParameter moduleTypeVersionParameter : moduleTypeVersion.getModuleTypeVersionParameters().values()) {
                String parameterValue = null;
                if (!moduleTypeVersionParameter.getMode().equals(ModuleTypeVersionParameter.OUT)) {
                    parameterValue = getInputParameter(getPath(moduleTypeVersionParameter.getParameterName()));
                }
                algorithm = replaceParameterPlaceholders(algorithm, moduleTypeVersionParameter, parameterValue, timeStamp);
            }
            
            historySubmodule.setUserScript(algorithm);

            // verify that the algorithm doesn't have any parameter placeholders left
            assertNoPlaceholders(algorithm, "#");

            // create outer block
            // declare all parameters, initialize IN and INOUT parameters
            String parameterDeclarations = "";
            for(ModuleTypeVersionParameter moduleTypeVersionParameter : moduleTypeVersion.getModuleTypeVersionParameters().values()) {
                String parameterName = moduleTypeVersionParameter.getParameterName();
                String parameterPath = getPath(parameterName);
                String parameterPathNames = getPathNames(parameterName);
                String parameterTimeStamp = parameterName + "_" + timeStamp;
                String sqlParameterType = moduleTypeVersionParameter.getSqlParameterType();
                String parameterValue = null;
                if (moduleTypeVersionParameter.getMode().equals(ModuleTypeVersionParameter.OUT)) {
                    parameterValue = null;
                    parameterDeclarations += "    " + parameterTimeStamp + " " + sqlParameterType + ";\n";
                } else { // IN or INOUT
                    parameterValue = getInputParameter(parameterName);
                    boolean isSet = (parameterValue != null) && (parameterValue.trim().length() > 0);
                    if (isSet) {
                        parameterDeclarations += "    " + parameterTimeStamp + " " + sqlParameterType + " := CAST('" + parameterValue + "' AS " + sqlParameterType + ");\n";
                    } else {
                        parameterValue = null;
                        parameterDeclarations += "    " + parameterTimeStamp + " " + sqlParameterType + " := NULL;\n";
                    }
                }
                if (moduleInternalParameters.containsKey(parameterPath)) {
                    ModuleInternalParameter moduleInternalParameter = moduleInternalParameters.get(parameterPath);
                    if (moduleInternalParameter.getKeep()) {
                        HistoryInternalParameter historyInternalParameter = new HistoryInternalParameter();
                        historyInternalParameter.setHistory(history);
                        historyInternalParameter.setParameterPath(parameterPath);
                        historyInternalParameter.setParameterPathNames(parameterPathNames);
                        historyInternalParameter.setValue(parameterValue);
                        historyInternalParameters.put(parameterPath, historyInternalParameter);
                    }
                }
            }
            history = modulesDAO.updateHistory(history, entityManager);
            
            // return the values of all INOUT and OUT parameters as a result set
            String outputParameters = "";
            for(ModuleTypeVersionParameter moduleTypeVersionParameter : moduleTypeVersion.getModuleTypeVersionParameters().values()) {
                String parameterName = moduleTypeVersionParameter.getParameterName();
                String parameterTimeStamp = parameterName + "_" + timeStamp; 
                if (!moduleTypeVersionParameter.getMode().equals(ModuleTypeVersionParameter.IN)) {
                    if (!outputParameters.isEmpty()) {
                        outputParameters += "UNION ALL\n";
                    }
                    outputParameters += "SELECT '" + parameterName + "' AS name, CAST(" + parameterTimeStamp + " AS text) AS value\n";
                }
            }
            
            String outputParametersTableName = "";
            String selectOutputParameters = "";
            if (!outputParameters.isEmpty()) {
                outputParametersTableName = "output_parameters_" + userTimeStamp;
                String outputParametersTable = "DROP TABLE IF EXISTS " + outputParametersTableName + ";\n" +
                                               "CREATE TEMPORARY TABLE " + outputParametersTableName + " AS\n";
                outputParameters = "\n\n-- output parameters result set\n\n" + outputParametersTable + outputParameters + ";\n";
                selectOutputParameters = "\nSELECT * FROM " + outputParametersTableName + ";\n\n" +
                                         "DROP TABLE IF EXISTS " + outputParametersTableName + ";\n\n";
            }
    
            String userTag = userTimeStamp + "_user_script";

            if (parameterDeclarations.isEmpty()) {
                algorithm = "\nDO $" + userTag + "$\nBEGIN\n" + viewDefinitions + algorithm + "\nEND $" + userTag + "$;\n";
            } else {
                algorithm = "\nDO $" + userTag + "$\nDECLARE\n" + parameterDeclarations + "BEGIN\n" + viewDefinitions + algorithm + outputParameters + "\nEND $" + userTag + "$;\n" + selectOutputParameters;
            }
    
            // execute setup script
            
            String setupScript = getGrantPermissionsScript(userTimeStamp, outputDatasetTables);
            
            try {
                historySubmodule.setSetupScript(setupScript);
                historySubmodule.setStatus(History.SETUP_SCRIPT);
                
                historySubmodule.addLogMessage(History.INFO, "Starting setup script.");
                
                historySubmodule = modulesDAO.updateHistorySubmodule(historySubmodule, entityManager);

                statement = connection.createStatement();
                statement.execute(setupScript);
                
            } catch (Exception e) {
                throw new EmfException(SETUP_SCRIPT_ERROR + e.getMessage());
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
            
            // execute algorithm
            
            // create new connection and login as the temporary user

            Connection userConnection = null; 
            try {
                historySubmodule.setUserScript(algorithm);
                historySubmodule.setStatus(History.USER_SCRIPT);
                
                historySubmodule.addLogMessage(History.INFO, "Starting user script (algorithm).");
                
                historySubmodule = modulesDAO.updateHistorySubmodule(historySubmodule, entityManager);
                
                userConnection = getUserConnection(userTimeStamp, getTempUserPassword());
                userConnection.setAutoCommit(true);
                statement = userConnection.createStatement();
                statement.execute(algorithm);

                // get the values of all INOUT and OUT parameters
                while(statement.getMoreResults()) {
                    ResultSet resultSet = statement.getResultSet();
                    while(resultSet.next()) {
                        String name = resultSet.getString(1);
                        String value = resultSet.getString(2);
                        
                        setOutputParameter(name, value);
                        
                        String parameterPath = getPath(name); 
                        if (historyInternalParameters.containsKey(parameterPath)) {
                            historyInternalParameters.get(parameterPath).setValue(value);
                        }
                    }
                }

                // TODO verify that all output parameters have been set
                
                // update the record counts for the output datasets
                if (outputDatasetVersions.size() > 0) {
                    historySubmodule.addLogMessage(History.INFO, "Updating the number of records for the OUT and INOUT datasets:");
                    for(Version version : outputDatasetVersions) {
                        EmfDataset dataset = datasetDAO.getDataset(entityManager, version.getDatasetId());
                        int recordCount = updateVersion(dataset, version, dbServer, entityManager, datasetDAO, user);
                        String message = String.format("Dataset \"%s\" version %d has %d records.", dataset.getName(), version.getVersion(), recordCount);
                        historySubmodule.addLogMessage(History.INFO, message);
                    }
                }
                
                historySubmodule.addLogMessage(History.INFO, "User script (algorithm) completed successfully.");
                
            } catch (Exception e) {
                throw new EmfException(USER_SCRIPT_ERROR + e.getMessage());
            } finally {
                if (statement != null) {
                    try {
                        statement.close();
                    } catch (SQLException e) {
                        // ignore
                    }
                    statement = null;
                }

                if (userConnection != null) {
                    userConnection.close();
                    userConnection = null;
                }
            }
            
            historySubmodule.setStatus(History.TEARDOWN_SCRIPT);
            executeTeardownScript(outputDatasetTables);
            
            historySubmodule.setStatus(History.COMPLETED);
            historySubmodule.setResult(History.SUCCESS);
            
            setFinalStatusMessage("Completed running submodule '" + getPathNames() + "': " + historySubmodule.getResult());
            
            historySubmodule.addLogMessage(History.SUCCESS, getFinalStatusMessage());
            
            historySubmodule = modulesDAO.updateHistorySubmodule(historySubmodule, entityManager);
            
        } catch (Exception e) {
            
            String eMessage = e.getMessage();
            
            historySubmodule.setResult(History.FAILED);
            historySubmodule.setErrorMessage(eMessage);

            if (eMessage.startsWith(SETUP_SCRIPT_ERROR)) {
                errorMessage = eMessage + "\n\n" + getLineNumberedScript(historySubmodule.getSetupScript()) + "\n";
                try {
                    executeTeardownScript(outputDatasetTables);
                } catch (Exception e2) {
                    errorMessage += "\n\n" + e2.getMessage() + "\n\n" + getLineNumberedScript(historySubmodule.getTeardownScript()) + "\n";
                }
            } else if (eMessage.startsWith(USER_SCRIPT_ERROR)) {
                errorMessage = eMessage + "\n\n" + getLineNumberedScript(historySubmodule.getUserScript()) + "\n";
                try {
                    executeTeardownScript(outputDatasetTables);
                } catch (Exception e2) {
                    errorMessage += "\n\n" + e2.getMessage() + "\n\n" + getLineNumberedScript(historySubmodule.getTeardownScript()) + "\n";
                }
            } else if (eMessage.startsWith(TEARDOWN_SCRIPT_ERROR)) {
                errorMessage = eMessage + "\n\n" + getLineNumberedScript(historySubmodule.getTeardownScript()) + "\n";
            } else {
                errorMessage = eMessage;
                try {
                    executeTeardownScript(outputDatasetTables);
                } catch (Exception e2) {
                    errorMessage += "\n\n" + e2.getMessage() + "\n\n" + getLineNumberedScript(historySubmodule.getTeardownScript()) + "\n";
                }
            }
            
            setFinalStatusMessage("Completed running submodule '" + getPathNames() + "': " + historySubmodule.getStatus() + " " + historySubmodule.getResult() + "\n\n" + errorMessage);
            
            historySubmodule.addLogMessage(History.ERROR, getFinalStatusMessage());
            
            historySubmodule = modulesDAO.updateHistorySubmodule(historySubmodule, entityManager);
            
        } finally {
            history = modulesDAO.updateHistory(history, entityManager);
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
}
