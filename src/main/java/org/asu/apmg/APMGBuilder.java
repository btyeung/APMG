package org.asu.apmg;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import org.apache.commons.io.FileUtils;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * @author aesanch2
 */
public class APMGBuilder extends Builder {

    private APMGGit git;
    private boolean rollbackEnabled, updatePackageEnabled, forceInitialBuild;

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public APMGBuilder(Boolean rollbackEnabled,
                       Boolean updatePackageEnabled,
                       Boolean forceInitialBuild) {
        this.rollbackEnabled = rollbackEnabled;
        this.updatePackageEnabled = updatePackageEnabled;
        this.forceInitialBuild = forceInitialBuild;
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {
        String newCommit;
        String prevCommit;
        String jenkinsGitUserName;
        String jenkinsGitEmail;
        String workspaceDirectory;
        String jobName;
        String buildTag;
        String buildNumber;
        String jenkinsHome;
        ArrayList<String> listOfDestructions, listOfUpdates;
        ArrayList<APMGMetadataObject> members;
        EnvVars envVars;
        List<ParameterValue> parameterValues;

        try{
            //Load our environment variables for the job
            envVars = build.getEnvironment(listener);

            newCommit = envVars.get("GIT_COMMIT");
            prevCommit = envVars.get("GIT_PREVIOUS_SUCCESSFUL_COMMIT");
            jenkinsGitUserName = envVars.get("GIT_COMMITTER_NAME");
            jenkinsGitEmail = envVars.get("GIT_COMMITTER_EMAIL");
            workspaceDirectory = envVars.get("WORKSPACE");
            jobName = envVars.get("JOB_NAME");
            buildTag = envVars.get("BUILD_TAG");
            buildNumber = envVars.get("BUILD_NUMBER");
            jenkinsHome = envVars.get("JENKINS_HOME");

            //Create a deployment space for this job within the workspace
            File deployStage = new File(workspaceDirectory + "/apmg");
            
            //check if apmg folder exists, and if so, remove it to start clean
            if(deployStage.exists()){
                FileUtils.deleteDirectory(deployStage);
            }deployStage.mkdirs();

            //Put the deployment stage location into the environment as a variable
            parameterValues = new ArrayList<ParameterValue>();
            
            //BYeung - change repo structure to match ours
            parameterValues.add(new StringParameterValue("APMG_DEPLOY", deployStage.getPath()+"/src"));

            //TODO BYeung modify this for appropriate.  We need to replicate the src/ structure that we don't have
            //BY wonder if it works if I go one level deeper
            String pathToRepo = workspaceDirectory + "/src/.git";

            //This was the initial commit to the repo or the first build
            if (prevCommit == null || getForceInitialBuild()){
                prevCommit = null;
                listener.getLogger().println("[SCTY] - did not find previous successful commit");
                git = new APMGGit(pathToRepo, newCommit);
            }
            //If we have a previous successful commit from the git plugin
            else{
                listener.getLogger().println("[SCTY] - found previous successful commit: " + prevCommit);
                git = new APMGGit(pathToRepo, newCommit, prevCommit);
            }

            //Get our change sets
            listOfDestructions = git.getDeletions();
            listOfUpdates = git.getNewChangeSet();

            //Generate the manifests
            members = APMGUtility.generateManifests(listOfDestructions, listOfUpdates, deployStage.getPath());
            listener.getLogger().println("[APMG] - Created deployment package.");

            //Copy the files to the deployStage
            //BY this seems to be where the magic happens
            /**
             * Copies all necessary files to the deployment stage.
             * @param members The list of metadata members to replicate.
             * @param sourceDir The directory where the members are located.
             * @param destDir The destination to copy the members to.
             * @throws IOException
             */

            listener.getLogger().println("[SCTY] - copying changed files into workspace. # of changes: " + members.size());

            APMGUtility.replicateMembers(members, workspaceDirectory + "/src", deployStage.getPath());

            //Check for rollback
            if (getRollbackEnabled() && prevCommit != null){
                String rollbackDirectory = jenkinsHome + "/jobs/" + jobName + "/builds/" + buildNumber + "/rollback";
                File rollbackStage = new File(rollbackDirectory);
                if(rollbackStage.exists()){
                    FileUtils.deleteDirectory(rollbackStage);
                }rollbackStage.mkdirs();

                //Get our lists
                ArrayList<String> listOfOldItems = git.getOldChangeSet();
                ArrayList<String> listOfAdditions = git.getAdditions();

                //Generate the manifests for the rollback package
                ArrayList<APMGMetadataObject> rollbackMembers =
                        APMGUtility.generateManifests(listOfAdditions, listOfOldItems, rollbackDirectory);

                //Copy the files to the rollbackStage and zip up the rollback stage
                git.getPrevCommitFiles(rollbackMembers, rollbackDirectory);
                String zipFile = APMGUtility.zipRollbackPackage(rollbackStage, buildTag);
                FileUtils.deleteDirectory(rollbackStage);
                parameterValues.add(new StringParameterValue("APMG_ROLLBACK", zipFile));
                listener.getLogger().println("[APMG] - Created rollback package at " + rollbackDirectory);
            }

            //Check to see if we need to update the repository's package.xml file
            if(getUpdatePackageEnabled()){
                //Byeung modified folder structure for solarcity salesforce
                boolean updateRequired = git.updatePackageXML(workspaceDirectory + "/package.xml",
                        jenkinsGitUserName, jenkinsGitEmail);
                if (updateRequired)
                    //BY increased logging
                    listener.getLogger().println("[APMG] - Updated repository package.xml file at " + workspaceDirectory + "/package.xml");
            }

            build.addAction(new ParametersAction(parameterValues));
        }catch(Exception e){
            e.printStackTrace(listener.getLogger());
            return false;
        }

        return true;
    }

    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    /**
     * Descriptor for {@link APMGBuilder}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {
        /**
         * In order to load the persisted global configuration, you have to
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "APMG";
        }
    }

    public boolean getRollbackEnabled() { return rollbackEnabled;}

    public boolean getUpdatePackageEnabled() { return updatePackageEnabled; }

    public boolean getForceInitialBuild() { return forceInitialBuild; }
}

