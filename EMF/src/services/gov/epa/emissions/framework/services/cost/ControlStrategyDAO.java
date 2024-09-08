package gov.epa.emissions.framework.services.cost;

import gov.epa.emissions.commons.db.DbServer;
import gov.epa.emissions.commons.security.User;
import gov.epa.emissions.framework.services.DbServerFactory;
import gov.epa.emissions.framework.services.EmfException;
import gov.epa.emissions.framework.services.basic.BasicSearchFilter;
import gov.epa.emissions.framework.services.basic.EmfProperty;
import gov.epa.emissions.framework.services.basic.SearchDAOUtility;
import gov.epa.emissions.framework.services.cost.controlStrategy.ControlStrategyConstraint;
import gov.epa.emissions.framework.services.cost.controlStrategy.ControlStrategyResult;
import gov.epa.emissions.framework.services.cost.controlStrategy.StrategyGroup;
import gov.epa.emissions.framework.services.cost.controlStrategy.StrategyResultType;
import gov.epa.emissions.framework.services.data.DataServiceImpl;
import gov.epa.emissions.framework.services.data.DataServiceImpl.DeleteType;
import gov.epa.emissions.framework.services.data.DatasetDAO;
import gov.epa.emissions.framework.services.data.EmfDataset;
import gov.epa.emissions.framework.services.persistence.EmfPropertiesDAO;
import gov.epa.emissions.framework.services.persistence.HibernateFacade;
import gov.epa.emissions.framework.services.persistence.HibernateFacade.CriteriaBuilderQueryRoot;
import gov.epa.emissions.framework.services.persistence.HibernateSessionFactory;
import gov.epa.emissions.framework.services.persistence.LockingScheme;
import gov.epa.emissions.framework.tasks.DebugLevels;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Join;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;

import org.apache.commons.lang3.StringUtils;
import org.hibernate.HibernateException;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.hibernate.query.Query;

public class ControlStrategyDAO {
    private LockingScheme lockingScheme;

    private HibernateFacade hibernateFacade;

    private HibernateSessionFactory sessionFactory;

    private DbServerFactory dbServerFactory;
    
    private DatasetDAO datasetDao;

    public ControlStrategyDAO() {
        lockingScheme = new LockingScheme();
        hibernateFacade = new HibernateFacade();
        this.datasetDao = new DatasetDAO();
    }

    public ControlStrategyDAO(DbServerFactory dbServerFactory, HibernateSessionFactory sessionFactory) {
        this();
        this.dbServerFactory = dbServerFactory;
        this.sessionFactory = sessionFactory;
    }

    public int add(ControlStrategy element, Session session) {
        return addObject(element, session);
    }

    public void add(ControlStrategyConstraint element, Session session) {
        addObject(element, session);
    }

    public int add(ControlStrategyResult element, Session session) {
        return addObject(element, session);
    }

    private int addObject(Object obj, Session session) {
        return (Integer)hibernateFacade.add(obj, session);
    }

    public String getControlStrategyRunStatus(int controlStrategyId, Session session) {
        return (String)session.createQuery("select cS.runStatus from ControlStrategy cS where cS.id = " + controlStrategyId).uniqueResult();
    }

    public Long getControlStrategyRunningCount(Session session) {
        return (Long)session.createQuery("select count(*) as total from ControlStrategy cS where cS.runStatus = 'Running'").uniqueResult();
    }

    public List<ControlStrategy> getControlStrategiesByRunStatus(String runStatus, Session session) {
//        Criterion critRunStatus = Restrictions.eq("runStatus", runStatus);
//        return hibernateFacade.get(ControlStrategy.class, critRunStatus, Order.asc("lastModifiedDate"), session);
//
        return session
                .createQuery("select new ControlStrategy(cS.id, cS.name) from ControlStrategy cS where cS.runStatus = :runStatus order by cS.lastModifiedDate", ControlStrategy.class)
                .setParameter("runStatus", runStatus)
                .list();
    }

    public void setControlStrategyRunStatusAndCompletionDate(int controlStrategyId, String runStatus, Date completionDate, Session session) {
        Transaction tx = null;
        try {
            tx = session.beginTransaction();
            session.createQuery("update ControlStrategy set runStatus = :status, lastModifiedDate = :date, completionDate = :completionDate where id = :id")
            .setParameter("status", runStatus)
            .setParameter("date", new Date())
            .setParameter("completionDate", completionDate)
            .setParameter("id", Integer.valueOf(controlStrategyId))
            .executeUpdate();
            tx.commit();
        } catch (HibernateException e) {
            tx.rollback();
            throw e;
        }
    }

    // return ControlStrategies orderby name
    public List<ControlStrategy> all(Session session) {

//        "Name", "Last Modified", "Run Status", "Region", 
//        "Target Pollutant", "Total Cost", "Reduction", 
//        "Project", "Strategy Type", "Cost Year", 
//        "Inv. Year", "Creator"
//        element.getName(), format(element.getLastModifiedDate()), element.getRunStatus(), region(element),
//        element.getTargetPollutant(), getTotalCost(element.getId()), getReduction(element.getId()), 
//        project(element), analysisType(element), costYear(element), 
//        "" + (element.getInventoryYear() != 0 ? element.getInventoryYear() : ""), 
//        element.getCreator().getName()
        return session
                .createQuery("select new ControlStrategy(cS.id, cS.name, " +
                    "cS.lastModifiedDate, cS.runStatus, " +
                    "R, TP, " +
                    "P, ST, " +
                    "cS.costYear, cS.inventoryYear, " +
    //                "cS.creator, (select sum(sR.totalCost) from ControlStrategyResult sR where sR.controlStrategyId = cS.id), (select sum(sR.totalReduction) from ControlStrategyResult sR where sR.controlStrategyId = cS.id)) " +
                    "cS.creator, cS.totalCost, cS.totalReduction, cS.isFinal) " +
                    "from ControlStrategy as cS " +
                    "left join cS.targetPollutant as TP " +
                    "left join cS.strategyType as ST " +
                    "left join cS.region as R " +
                    "left join cS.project as P " +
                    "order by cS.name", ControlStrategy.class)
                .list();
        //return hibernateFacade.getAll(ControlStrategy.class, Order.asc("name"), session);
    }

    public List<ControlStrategy> getControlStrategies(Session session, BasicSearchFilter searchFilter) throws EmfException {
        String hql = "select distinct new ControlStrategy(cs.id, cs.name, " +
                "cs.lastModifiedDate, cs.runStatus, " +
                "region, targetPollutant, " +
                "project, strategyType, " +
                "cs.costYear, cs.inventoryYear, " +
//                "cS.creator, (select sum(sR.totalCost) from ControlStrategyResult sR where sR.controlStrategyId = cS.id), (select sum(sR.totalReduction) from ControlStrategyResult sR where sR.controlStrategyId = cS.id)) " +
                "cs.creator, cs.totalCost, cs.totalReduction, cs.isFinal) " +
                "from ControlStrategy as cs " +
                "left join cs.targetPollutant as targetPollutant " +
                "left join cs.strategyType as strategyType " +
                "left join cs.region as region " +
                "left join cs.project as project " +
                "left join cs.creator as creator " +
                "left join cs.controlStrategyInputDatasets as inputDataset " +
                "left join cs.controlPrograms as controlProgram " +
                "left join cs.controlMeasures as controlMeasure " +
                "left join cs.controlMeasureClasses as controlMeasureClass ";
        //
        if (StringUtils.isNotBlank(searchFilter.getFieldName())
                && StringUtils.isNotBlank(searchFilter.getFieldValue())) {
            String whereClause = SearchDAOUtility.buildSearchCriterion(new ControlStrategyFilter(), searchFilter);
            if (StringUtils.isNotBlank(whereClause))
                hql += " where " + whereClause;
        }
        return session.createQuery(hql, ControlStrategy.class).list();
    }
//    // return ControlStrategies orderby name
//    public List test(Session session) {
//        //check if dataset is a input inventory for some strategy (via the StrategyInputDataset table)
//        List list = session.createQuery("select cS.name from ControlStrategy as cS inner join cS.controlStrategyInputDatasets as iDs inner join iDs.inputDataset as iD with iD.id = 1221").list();
//        //check if dataset is a input inventory for some strategy (via the StrategyResult table, could be here for historical reasons)
//        list = session.createQuery("select cS.name from ControlStrategyResult sR, ControlStrategy cS where sR.controlStrategyId = cS.id and sR.inputDataset.id = 1221").list();
//        //check if dataset is a detailed result dataset for some strategy
//        list = session.createQuery("select cS.name from ControlStrategyResult sR, ControlStrategy cS where sR.controlStrategyId = cS.id and sR.detailedResultDataset.id = 1221").list();
//        //check if dataset is a controlled inventory for some strategy
//        list = session.createQuery("select cS.name from ControlStrategyResult sR, ControlStrategy cS where sR.controlStrategyId = cS.id and sR.controlledInventoryDataset.id = 1221").list();
//        //check if dataset is used as a region/county dataset for specific strategy measures
//        list = session.createQuery("select cS.name from ControlStrategy as cS inner join cS.controlMeasures as cM inner join cM.regionDataset as rD with rD.id = 1221").list();
//        //check if dataset is used as a region/county dataset for specific strategy
//        list = session.createQuery("select cS.name from ControlStrategy cS where cS.countyDataset.id = 1221").list();
//
//        return list;
//    }

    public List<StrategyType> getAllStrategyTypes(Session session) {
        CriteriaBuilderQueryRoot<StrategyType> criteriaBuilderQueryRoot = hibernateFacade.getCriteriaBuilderQueryRoot(StrategyType.class, session);
        CriteriaBuilder builder = criteriaBuilderQueryRoot.getBuilder();
        Root<StrategyType> root = criteriaBuilderQueryRoot.getRoot();

        return hibernateFacade.getAll(criteriaBuilderQueryRoot, builder.asc(root.get("name")), session);
    }

//    // TODO: gettig all the strategies to obtain the lock--- is it a good idea?
//    public ControlStrategy obtainLocked(User owner, ControlStrategy element, Session session) {
//        return (ControlStrategy) lockingScheme.getLocked(owner, current(element, session), session);
//    }
//
    public ControlStrategy obtainLocked(User owner, int id, Session session) {
        return (ControlStrategy) lockingScheme.getLocked(owner, current(id, session), session);
    }

//    public void releaseLocked(ControlStrategy locked, Session session) {
//        ControlStrategy current = current(locked, session);
//        String runStatus = current.getRunStatus();
//        if (runStatus == null || !runStatus.equalsIgnoreCase("Running"))
//            lockingScheme.releaseLock(current, session);
//    }

    public void releaseLocked(User user, int id, Session session) {
        ControlStrategy current = getById(id, session);
        String runStatus = current.getRunStatus();
        if (runStatus == null || !runStatus.equalsIgnoreCase("Running"))
            lockingScheme.releaseLock(user, current, session);
    }

    public ControlStrategy update(ControlStrategy locked, Session session) throws EmfException {
        return (ControlStrategy) lockingScheme.releaseLockOnUpdate(locked, current(locked, session), session);
    }

    public void updateWithoutLock(ControlStrategy controlStrategy, Session session) throws EmfException {
        hibernateFacade.saveOrUpdate(controlStrategy, session);
    }
    
    public ControlStrategy updateWithLock(ControlStrategy locked, Session session) throws EmfException {
        return (ControlStrategy) lockingScheme.renewLockOnUpdate(locked, current(locked, session), session);
    }

    private ControlStrategy current(ControlStrategy strategy, Session session) {
        return current(strategy.getId(), session);
    }

    public boolean canUpdate(ControlStrategy controlStrategy, Session session) {
        if (!exists(controlStrategy.getId(), session)) {
            return false;
        }

        ControlStrategy current = current(controlStrategy.getId(), session);

        session.clear();// clear to flush current

        if (current.getName().equals(controlStrategy.getName()))
            return true;

        return !nameUsed(controlStrategy.getName(), session);
    }

    public boolean nameUsed(String name, Session session) {
        return hibernateFacade.nameUsed(name, ControlStrategy.class, session);
    }

    private ControlStrategy current(int id, Session session) {
        return hibernateFacade.current(id, ControlStrategy.class, session);
    }

    public boolean exists(int id, Session session) {
        return hibernateFacade.exists(id, ControlStrategy.class, session);
    }

    public void remove(ControlStrategy strategy, Session session) {
        if (strategy.getConstraint() != null) hibernateFacade.remove(strategy.getConstraint(), session);
        hibernateFacade.remove(strategy, session);
    }

    public void remove(ControlStrategyResult result, Session session) {
        hibernateFacade.remove(result, session);
    }

    public StrategyResultType getDetailedStrategyResultType(Session session) {
        return getStrategyResultType(StrategyResultType.detailedStrategyResult, session);
    }

    public StrategyResultType getStrategyResultType(String name, Session session) {
        return hibernateFacade.load(StrategyResultType.class, "name", name, session);
    }

    public StrategyResultType[] getOptionalStrategyResultTypes(Session session) {
        CriteriaBuilder builder = session.getCriteriaBuilder();
        CriteriaQuery<StrategyResultType> criteriaQuery = builder.createQuery(StrategyResultType.class);
        Root<StrategyResultType> root = criteriaQuery.from(StrategyResultType.class);

        criteriaQuery.select(root);
        criteriaQuery.where(builder.equal(root.get("optional"), true));

        return session.createQuery(criteriaQuery).getResultList().toArray(new StrategyResultType[0]);
    }

    public StrategyResultType getSummaryStrategyResultType(Session session) {
        return getStrategyResultType(StrategyResultType.strategyMeasureSummary, session);
    }

    public ControlStrategyResult getControlStrategyResult(int controlStrategyId, int inputDatasetId, 
            int detailedResultDatasetId, Session session) {
        CriteriaBuilderQueryRoot<ControlStrategyResult> criteriaBuilderQueryRoot = hibernateFacade.getCriteriaBuilderQueryRoot(ControlStrategyResult.class, session);
        CriteriaBuilder builder = criteriaBuilderQueryRoot.getBuilder();
        Root<ControlStrategyResult> root = criteriaBuilderQueryRoot.getRoot();
        Join<EmfDataset, ControlStrategyResult> drdJoin = root.join("detailedResultDataset", javax.persistence.criteria.JoinType.INNER);

        Predicate critControlStrategyId = builder.equal(root.get("controlStrategyId"), controlStrategyId);
        Predicate critInputDatasetId = builder.equal(root.get("inputDatasetId"), inputDatasetId);
        Predicate critDetailedResultDatasetId = builder.equal(drdJoin.get("id"), detailedResultDatasetId);
        return hibernateFacade.load(session, criteriaBuilderQueryRoot, new Predicate[] {critControlStrategyId, critInputDatasetId, critDetailedResultDatasetId});
    }

    public ControlStrategyResult getControlStrategyResult(int id, Session session) {
        return hibernateFacade.load(ControlStrategyResult.class, "id", Integer.valueOf(id), session);
    }

//    private void updateControlStrategyIds(ControlStrategy controlStrategy, Session session) {
//        Criterion c1 = Restrictions.eq("name", controlStrategy.getName());
//        List list = hibernateFacade.get(ControlStrategy.class, c1, session);
//        if (!list.isEmpty()) {
//            ControlStrategy cs = (ControlStrategy) list.get(0);
//            controlStrategy.setId(cs.getId());
//        }
//    }
//
    public void updateControlStrategyResult(ControlStrategyResult result, Session session) {
        hibernateFacade.saveOrUpdate(result, session);
    }

    public String controlStrategyRunStatus(int id, Session session) {
        ControlStrategy controlStrategy = hibernateFacade.current(id, ControlStrategy.class, session);
        return controlStrategy.getRunStatus();
    }

//    public void removeControlStrategyResult(ControlStrategy controlStrategy, Session session) {
//        Criterion c = Restrictions.eq("controlStrategyId", Integer.valueOf(controlStrategy.getId()));
//        List list = hibernateFacade.get(ControlStrategyResult.class, c, session);
//        for (int i = 0; i < list.size(); i++) {
//            ControlStrategyResult result = (ControlStrategyResult) list.get(i);
//            hibernateFacade.delete(result,session);
//        }
//    }

    public void removeControlStrategyResults(int controlStrategyId, Session session) {
        String hqlDelete = "delete ControlStrategyResult sr where sr.controlStrategyId = :controlStrategyId";
        Transaction tx = null;
        try {
            tx = session.beginTransaction();
            session.createQuery( hqlDelete )
                .setParameter("controlStrategyId", Integer.valueOf(controlStrategyId))
                .executeUpdate();
            tx.commit();
        } catch (RuntimeException e) {
            tx.rollback();
            throw e;
        }
    }

    public void removeControlStrategyResult(int controlStrategyId, int resultId, Session session) {
        String hqlDelete = "delete ControlStrategyResult sr where sr.id = :resultId and sr.controlStrategyId = :controlStrategyId";
        Transaction tx = null;
        try {
            tx = session.beginTransaction();
            session.createQuery( hqlDelete )
                .setParameter("resultId", Integer.valueOf(resultId))
                .setParameter("controlStrategyId", Integer.valueOf(controlStrategyId))
                .executeUpdate();
            tx.commit();
        } catch (RuntimeException e) {
            tx.rollback();
            throw e;
        }
    }

    public ControlStrategy getByName(String name, Session session) {
        return hibernateFacade.load(ControlStrategy.class, "name", name, session);
    }

    public ControlStrategy getById(int id, Session session) {
        return hibernateFacade.load(ControlStrategy.class, "id", Integer.valueOf(id), session);
    }

    public List<ControlStrategyResult> getControlStrategyResults(int controlStrategyId, Session session) {
        CriteriaBuilder builder = session.getCriteriaBuilder();
        CriteriaQuery<ControlStrategyResult> criteriaQuery = builder.createQuery(ControlStrategyResult.class);
        Root<ControlStrategyResult> root = criteriaQuery.from(ControlStrategyResult.class);

        criteriaQuery.select(root);

        criteriaQuery.orderBy(builder.desc(root.get("startTime")));

        criteriaQuery.where(builder.equal(root.get("controlStrategyId"), controlStrategyId));

        return session.createQuery(criteriaQuery).getResultList();
    }
    
    public void removeResultDatasets(EmfDataset[] datasets, User user, Session session, DbServer dbServer) throws EmfException {
        if (datasets != null) {
            try {
                deleteDatasets(datasets, user, session);
                datasetDao.deleteDatasets(datasets, dbServer, session);
            } catch (EmfException e) {
                if (DebugLevels.DEBUG_12())
                    System.out.println(e.getMessage());
                
                throw new EmfException(e.getMessage());
            }
        }
    }
    
    public void deleteDatasets(EmfDataset[] datasets, User user, Session session) throws EmfException {
        EmfDataset[] lockedDatasets = getLockedDatasets(datasets, user, session);
        
        if (lockedDatasets == null)
            return;
        
        try {
            new DataServiceImpl(dbServerFactory, sessionFactory).deleteDatasets(user, lockedDatasets, DeleteType.CONTROL_STRATEGY);
        } catch (EmfException e) {
//            releaseLocked(lockedDatasets, user, session);
//            throw new EmfException(e.getMessage());
            if (!e.getType().equals(EmfException.MSG_TYPE))
                throw new EmfException(e.getMessage());
        } finally {
            releaseLocked(lockedDatasets, user, session);
        }
    }
    
    private EmfDataset[] getLockedDatasets(EmfDataset[] datasets, User user, Session session) {
        List<EmfDataset> lockedList = new ArrayList<EmfDataset>();
        
        for (int i = 0; i < datasets.length; i++) {
            EmfDataset locked = obtainLockedDataset(datasets[i], user, session);
            if (locked == null) {
                releaseLocked(lockedList.toArray(new EmfDataset[0]), user, session);
                return null;
            }
            
            lockedList.add(locked);
        }
        
        return lockedList.toArray(new EmfDataset[0]);
    }

    private EmfDataset obtainLockedDataset(EmfDataset dataset, User user, Session session) {
        EmfDataset locked = datasetDao.obtainLocked(user, dataset, session);
        return locked;
    }
    
    private void releaseLocked(EmfDataset[] lockedDatasets, User user, Session session) {
        if (lockedDatasets.length == 0)
            return;
        
        for(int i = 0; i < lockedDatasets.length; i++)
            datasetDao.releaseLocked(user, lockedDatasets[i], session);
    }
//    public void removeResultDatasets(Integer[] ids, User user, Session session, DbServer dbServer) throws EmfException {
//        DatasetDAO dsDao = new DatasetDAO();
//        for (Integer id : ids ) {
//            EmfDataset dataset = dsDao.getDataset(session, id);
//
//            if (dataset != null) {
//                try {
//                    dsDao.remove(user, dataset, session);
//                    purgeDeletedDatasets(dataset, session, dbServer);
//                    session.flush();
//                    session.clear();
//                } catch (EmfException e) {
//                    if (DebugLevels.DEBUG_12())
//                        System.out.println(e.getMessage());
//                    
//                    throw new EmfException(e.getMessage());
//                }
//            }
//        }
//    }
    
//    private void purgeDeletedDatasets(EmfDataset dataset, Session session, DbServer dbServer) throws EmfException {
//        try {
//            DatasetDAO dao = new DatasetDAO();
//            dao.deleteDatasets(new EmfDataset[] {dataset}, dbServer, session);
//        } catch (Exception e) {
//            throw new EmfException(e.getMessage());
//        } finally {
//            //
//        }
//    }

    public Integer[] getResultDatasetIds(int controlStrategyId, Session session) {
        List<ControlStrategyResult> results = getControlStrategyResults(controlStrategyId, session);
        List<Integer> datasetLists = new ArrayList<Integer>();
        if(results != null){
            for (int i=0; i<results.size(); i++){
                if (results.get(i).getStrategyResultType().getName().equals(StrategyResultType.detailedStrategyResult)) {
                    if (results.get(i).getDetailedResultDataset() != null)
                        datasetLists.add(results.get(i).getDetailedResultDataset().getId());
                    if (results.get(i).getControlledInventoryDataset() != null)
                        datasetLists.add( results.get(i).getControlledInventoryDataset().getId());
                } else {
                    datasetLists.add( results.get(i).getDetailedResultDataset().getId());
                }
            }
        }
        if (datasetLists.size()>0)
            return datasetLists.toArray(new Integer[0]);
        return null; 
    }

    
    public EmfDataset[] getResultDatasets(int controlStrategyId, Session session) {
        List<ControlStrategyResult> results = getControlStrategyResults(controlStrategyId, session);
        List<EmfDataset> datasets = new ArrayList<EmfDataset>();
        if(results != null){
            for (int i=0; i<results.size(); i++){
                if (results.get(i).getDetailedResultDataset() != null)
                    datasets.add((EmfDataset)results.get(i).getDetailedResultDataset());
                if (results.get(i).getControlledInventoryDataset() != null)
                    datasets.add((EmfDataset)results.get(i).getControlledInventoryDataset());
            }
        }
        if (datasets.size()>0)
            return datasets.toArray(new EmfDataset[0]);
        return null; 
    }

    public EmfDataset[] getResultDatasets(int controlStrategyId, int resultId, Session session) {
        ControlStrategyResult result = getControlStrategyResult(resultId, session);
        List<EmfDataset> datasets = new ArrayList<EmfDataset>();
        if(result != null){
            if (result.getDetailedResultDataset() != null)
                datasets.add((EmfDataset)result.getDetailedResultDataset());
            if (result.getControlledInventoryDataset() != null)
                datasets.add((EmfDataset)result.getControlledInventoryDataset());
        }
        if (datasets.size()>0)
            return datasets.toArray(new EmfDataset[0]);
        return null; 
    }

    public void setControlStrategyRunStatus(int id, String runStatus, Date completionDate, Session session) {
        // NOTE Auto-generated method stub
        
    }

    public String getDefaultExportDirectory(Session session) {
        EmfProperty tmpDir = new EmfPropertiesDAO().getProperty("ImportExportTempDir", session);
        String dir = "";
        if (tmpDir != null)
            dir = tmpDir.getValue();
        return dir;
    }

    public String getStrategyRunStatus(Session session, int id) {
        return (String)session.createQuery("select cS.runStatus " +
                "from ControlStrategy cS where cS.id = " + id).uniqueResult();
    }

    public List<ControlStrategy> getControlStrategiesByControlMeasures(int[] cmIds, Session session) {
        List<ControlStrategy> list = new ArrayList<ControlStrategy>();
        String idList = "";
        for (int i = 0; i < cmIds.length; ++i) {
            idList += (i > 0 ? ","  : "") + cmIds[i];
        }
        try {
            Query<ControlStrategy> query = session.createQuery("select distinct cs "
                    + "FROM ControlStrategy AS cs "
                    + (cmIds != null && cmIds.length > 0 
                            ? "inner join cs.controlMeasures AS csm inner join csm.controlMeasure AS cm "
                               + "WHERE cm.id in (" + idList + ") " 
                            : "")
                    + "order by cs.name", ControlStrategy.class);
//            Query query = session.createQuery("select new ControlStrategy(cs.id, cs.name, cs.controlMeasures) "
//                    + "FROM ControlStrategy AS cs "
//                    + (cmIds != null && cmIds.length > 0 
//                            ? "inner join cs.controlMeasures AS csm inner join csm.controlMeasure AS cm "
//                               + "WHERE cm.id in (" + idList + ") " 
//                            : "")
//                    + "order by cs.name");
            query.setCacheable(true);
            list = query.list();
        } catch(Exception ex) {
            ex.printStackTrace();
        }
        return list;
    }
    
    public void finalizeControlStrategy(int controlStrategyId, String msg, Session session, int[] measureIdsToDelete) throws EmfException {
        Transaction tx = null;
        try {
            tx = session.beginTransaction();
            session
                .createQuery("update ControlStrategy set isFinal = :isFinal, description =  '' || "
                        + "description || '\n------\n' || :msg, lastModifiedDate = :date where id = :id")
                .setParameter("isFinal", true)
                .setParameter("msg", msg)
                .setParameter("date", new Date())
                .setParameter("id", Integer.valueOf(controlStrategyId))
                .executeUpdate();
            tx.commit();
            session.clear();
            
            //also need to purge measures that are being deleted...this is needed to keep hibernate list_index in synch...
            ControlStrategy cs = getById(controlStrategyId, session);
            List<ControlStrategyMeasure> measures = new ArrayList<ControlStrategyMeasure>();
            measures.addAll(Arrays.asList(cs.getControlMeasures()));
            for (ControlStrategyMeasure m : cs.getControlMeasures()) {
                for (int id : measureIdsToDelete) {
                    if (id == m.getControlMeasure().getId()) {
                        measures.remove(m);
                    }
                }
            }
            cs.setControlMeasures(measures.toArray(new ControlStrategyMeasure[0]));
            updateWithLock(cs, session);
            
        } catch (HibernateException e) {
            tx.rollback();
            throw e;
        } catch (EmfException e) {
            // NOTE Auto-generated catch block
            e.printStackTrace();
            throw e;
        }
    }

    public List<StrategyGroup> getAllStrategyGroups(Session session) {
        CriteriaBuilderQueryRoot<StrategyGroup> criteriaBuilderQueryRoot = hibernateFacade.getCriteriaBuilderQueryRoot(StrategyGroup.class, session);
        CriteriaBuilder builder = criteriaBuilderQueryRoot.getBuilder();
        Root<StrategyGroup> root = criteriaBuilderQueryRoot.getRoot();

        return hibernateFacade.getAll(criteriaBuilderQueryRoot, builder.asc(root.get("name")), session);
    }

    public StrategyGroup getGroupById(int id, Session session) {
        StrategyGroup group = hibernateFacade.load(StrategyGroup.class, "id", Integer.valueOf(id), session);
        return group;
    }

    public StrategyGroup getGroupByName(String name, Session session) {
        StrategyGroup group = hibernateFacade.load(StrategyGroup.class, "name", new String(name), session);
        return group;
    }

    public StrategyGroup obtainLockedGroup(User owner, int id, Session session) {
        return (StrategyGroup) lockingScheme.getLocked(owner, currentGroup(id, session), session);
    }

    public void releaseLockedGroup(User user, int id, Session session) {
        StrategyGroup current = getGroupById(id, session);
        lockingScheme.releaseLock(user, current, session);
    }

    public int addGroup(StrategyGroup group, Session session) {
        return addObject(group, session);
    }

    public boolean canUpdateGroup(StrategyGroup strategyGroup, Session session) {
        if (!exists(strategyGroup.getId(), session)) {
            return false;
        }

        StrategyGroup current = currentGroup(strategyGroup.getId(), session);

        session.clear();// clear to flush current

        if (current.getName().equals(strategyGroup.getName()))
            return true;

        return !nameUsed(strategyGroup.getName(), session);
    }

    private StrategyGroup currentGroup(int id, Session session) {
        return hibernateFacade.current(id, StrategyGroup.class, session);
    }
    
    public StrategyGroup updateGroupWithLock(StrategyGroup locked, Session session) throws EmfException {
        return (StrategyGroup) lockingScheme.renewLockOnUpdate(locked, currentGroup(locked, session), session);
    }

    private StrategyGroup currentGroup(StrategyGroup strategyGroup, Session session) {
        return currentGroup(strategyGroup.getId(), session);
    }
    
    public void removeGroup(StrategyGroup strategyGroup, Session session) {
        hibernateFacade.remove(strategyGroup, session);
    }

}
