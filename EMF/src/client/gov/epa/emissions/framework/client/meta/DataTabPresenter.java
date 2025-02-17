package gov.epa.emissions.framework.client.meta;

import java.awt.Cursor;

import gov.epa.emissions.framework.client.EmfSession;
import gov.epa.emissions.framework.client.meta.versions.VersionsView;
import gov.epa.emissions.framework.client.meta.versions.VersionsViewPresenter;
import gov.epa.emissions.framework.client.swingworker.LightSwingWorkerPresenter;
import gov.epa.emissions.framework.services.EmfException;
import gov.epa.emissions.framework.services.data.EmfDataset;

public class DataTabPresenter implements LightSwingWorkerPresenter {

    private DataTabView view;

    private EmfDataset dataset;

    private EmfSession session;

    public DataTabPresenter(DataTabView view, EmfDataset dataset, EmfSession session) {
        this.view = view;
        this.dataset = dataset;
        this.session = session;
    }

    public void doSave() {
        // No Op
    }

    public void doDisplay() {
        view.observe(this);
        view.display(dataset);
    }

    public void displayVersions(VersionsView versionsView) throws EmfException {
        VersionsViewPresenter versionsPresenter = new VersionsViewPresenter(dataset, session);
        versionsPresenter.display(versionsView);
    }

    public EmfDataset reloadDataset() throws EmfException {
        this.dataset = session.dataService().getDataset(dataset.getId());
        return dataset;
    }

    @Override
    public Object[] refreshProcessData() throws EmfException {
        return new EmfDataset[] { reloadDataset() };
    }

    @Override
    public void refreshDisplay(Object[] objs) throws EmfException {
        view.doRefresh(dataset);
    }

    @Override
    public Object[] swProcessData() throws EmfException {
        // NOTE Auto-generated method stub
        return null;
    }

    @Override
    public void swDisplay(Object[] objs) throws EmfException {
        // NOTE Auto-generated method stub

    }

    @Override
    public Object[] saveProcessData() throws EmfException {
        // NOTE Auto-generated method stub
        return null;
    }

    @Override
    public void saveDisplay(Object[] objs) throws EmfException {
        // NOTE Auto-generated method stub

    }

}
