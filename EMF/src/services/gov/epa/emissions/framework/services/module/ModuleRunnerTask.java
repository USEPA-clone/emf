package gov.epa.emissions.framework.services.module;

import gov.epa.emissions.commons.db.Datasource;
import gov.epa.emissions.commons.db.DbServer;
import gov.epa.emissions.commons.security.User;
import gov.epa.emissions.framework.services.DbServerFactory;
import gov.epa.emissions.framework.services.EmfException;
import gov.epa.emissions.framework.services.basic.Status;
import gov.epa.emissions.framework.services.basic.StatusDAO;
import gov.epa.emissions.framework.services.basic.UserDAO;
import gov.epa.emissions.framework.services.data.DatasetDAO;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Date;

import javax.persistence.EntityManager;
import javax.persistence.EntityManagerFactory;

// import EDU.oswego.cs.dl.util.concurrent.PooledExecutor;

public class ModuleRunnerTask {

    private int[] moduleIds;

    private User user;

    private DatasetDAO datasetDAO;
    
    private StatusDAO statusDAO;

    private ModulesDAO modulesDAO;
    
    private EntityManagerFactory entityManagerFactory;
    
    private EntityManager entityManager;

    private DbServerFactory dbServerFactory;

    private DbServer dbServer;
    private Connection connection;
    
    private Datasource datasource;
    
    // private PooledExecutor threadPool;

    private boolean verboseStatusLogging = true;

    public ModuleRunnerTask(int[] moduleIds, User user, 
            DbServerFactory dbServerFactory, EntityManagerFactory entityManagerFactory,
            boolean verboseStatusLogging) {
        this(moduleIds, user, dbServerFactory, entityManagerFactory);
        this.verboseStatusLogging = verboseStatusLogging;
    }

    public ModuleRunnerTask(int[] moduleIds, User user, 
            DbServerFactory dbServerFactory, EntityManagerFactory entityManagerFactory) {
        this.moduleIds = moduleIds;
        this.dbServerFactory = dbServerFactory;
        this.datasource = dbServerFactory.getDbServer().getEmissionsDatasource();
        this.entityManagerFactory = entityManagerFactory;
        this.datasetDAO = new DatasetDAO(dbServerFactory);
        this.statusDAO = new StatusDAO(entityManagerFactory);
        this.modulesDAO = new ModulesDAO();
        // this.threadPool = createThreadPool();
        UserDAO userDAO = new UserDAO();
        this.user = userDAO.get(user.getId(), entityManagerFactory.createEntityManager());
    }

//    private synchronized PooledExecutor createThreadPool() {
//        PooledExecutor threadPool = new PooledExecutor(20);
//        threadPool.setMinimumPoolSize(1);
//        threadPool.setKeepAliveTime(1000 * 60 * 3); // terminate after 3 (unused) minutes
//
//        return threadPool;
//    }

    public void run() throws EmfException {
        for(int moduleId : moduleIds) {
            runModule(moduleId);
        }
    }

    private void runModule(int moduleId) {
        try {
            entityManager = entityManagerFactory.createEntityManager();

            Module module = modulesDAO.getModule(moduleId, entityManager);
            if (module == null) {
                throw new EmfException("Module not found.");
            }
            runModule(module);
            
        } catch (Exception e) {
            setStatus("Failed to run module (ID = " + moduleId + "). " + e.getMessage());
            
        } finally {
            entityManager.close();
        }
    }
    
    private void runModule(Module module) throws EmfException {
        
        prepare("", module);
        
        String finalStatusMessage = "";
        
        boolean must_unlock_module = false;
        
        try {
            dbServer = dbServerFactory.getDbServer();
            connection = dbServer.getConnection();
            
            if (module.isLocked()) {
                if (!module.isLocked(user)) {
                    throw new EmfException("Module " + module.getName() + " locked by " + module.getLockOwner());
                }
            } else {
                module = modulesDAO.obtainLockedModule(user, module.getId(), entityManager);
                if (!module.isLocked(user)) {
                    throw new EmfException("Failed to lock module " + module.getName());
                }
                must_unlock_module = true;
            }
            
            StringBuilder error = new StringBuilder();
            if (!module.isValid(error)) {
                throw new EmfException("Module '" + module.getName() + "' is not valid: " + error.toString());
            }
            
            ModuleRunnerContext moduleRunnerContext = new ModuleRunnerContext(this, module);
            
            ModuleRunner moduleRunner = module.isComposite()
                                        ? new CompositeModuleRunner(moduleRunnerContext)
                                        : new SimpleModuleRunner(moduleRunnerContext);
            moduleRunner.run();
            
            finalStatusMessage = moduleRunner.getFinalStatusMessage();
            
        } catch (Exception e) {
            finalStatusMessage = "Completed running module '" + module.getName() + "'.\n" + e.getMessage();
            
        } finally {
            if (must_unlock_module) {
                try {
                    module = modulesDAO.releaseLockedModule(user, module.getId(), entityManager);
                    must_unlock_module = false;
                } catch (Exception e) {
                    // ignore
                }
            }

            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    // NOTE Auto-generated catch block
                    e.printStackTrace();
                }
                connection = null;
            }
            
            close(dbServer);
        }

        complete(finalStatusMessage);
    }
    
    private void close(DbServer dbServer) throws EmfException {
        try {
            if (dbServer != null && dbServer.isConnected())
                dbServer.disconnect();
        } catch (Exception e) {
            throw new EmfException("Could not close database connection." + e.getMessage());
        }
    }

    private void prepare(String suffixMsg, Module module) {
        if (verboseStatusLogging)
            setStatus("Started running module '" + module.getName() + "'." + suffixMsg);
    }

    private void complete(String finalStatusMessage) {
        if (verboseStatusLogging)
            setStatus(finalStatusMessage);
    }

    private void setStatus(String message) {
        Status endStatus = new Status();
        endStatus.setUsername(user.getUsername());
        endStatus.setType("Module Runner");
        endStatus.setMessage(message);
        endStatus.setTimestamp(new Date());

        statusDAO.add(endStatus);
    }

    public User getUser() {
        return user;
    }

    public DatasetDAO getDatasetDAO() {
        return datasetDAO;
    }
    
    public StatusDAO getStatusDAO() {
        return statusDAO;
    }

    public ModulesDAO getModulesDAO() {
        return modulesDAO;
    }
    
    public EntityManagerFactory getEntityManagerFactory() {
        return entityManagerFactory;
    }

    public EntityManager getEntityManager() {
        return entityManager;
    }

    public DbServerFactory getDbServerFactory() {
        return dbServerFactory;
    }

    public DbServer getDbServer() {
        return dbServer;
    }

    public Connection getConnection() {
        return connection;
    }

    public Datasource getDatasource() {
        return datasource;
    }
    
    public boolean getVerboseStatusLogging() {
        return verboseStatusLogging;
    }
}
