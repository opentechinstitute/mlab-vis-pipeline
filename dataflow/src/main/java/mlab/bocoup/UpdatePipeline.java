package mlab.bocoup;

import java.util.concurrent.TimeUnit;

import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.api.services.bigquery.model.TableRow;
import com.google.cloud.dataflow.sdk.Pipeline;
import com.google.cloud.dataflow.sdk.io.BigQueryIO.Write.CreateDisposition;
import com.google.cloud.dataflow.sdk.io.BigQueryIO.Write.WriteDisposition;
import com.google.cloud.dataflow.sdk.options.PipelineOptionsFactory;
import com.google.cloud.dataflow.sdk.runners.DataflowPipelineJob;
import com.google.cloud.dataflow.sdk.util.MonitoringUtil;
import com.google.cloud.dataflow.sdk.values.PCollection;

import mlab.bocoup.pipelineopts.UpdatePipelineOptions;
import mlab.bocoup.util.BigQueryIOHelpers;
import mlab.bocoup.util.Schema;

public class UpdatePipeline {
	
	private static final Logger LOG = LoggerFactory.getLogger(UpdatePipeline.class);
	
	/**
	 * To run, pass --timePeriod as either "day" or "hour".
	 */
	public static void main(String[] args) throws Exception {
		// get the pipeline options
		PipelineOptionsFactory.register(UpdatePipelineOptions.class);
		UpdatePipelineOptions options = PipelineOptionsFactory.fromArgs(args)
					.withValidation()
					.as(UpdatePipelineOptions.class);
		
	    String timePeriod = options.getTimePeriod();
	    String projectId = options.getProject();
	    
	    // Pipeline 1:
	    // ====  make daily update tables
	    UpdatePipelineOptions extractNewRowsOptions = options.cloneAs(UpdatePipelineOptions.class);
	    extractNewRowsOptions.setAppName("UpdatePipeline-ExtractRows");
	    Pipeline pipe = Pipeline.create(extractNewRowsOptions);
	    ExtractUpdateRowsPipeline dup = new ExtractUpdateRowsPipeline(pipe);
	    dup.setProjectId(projectId)
	    	.setTimePeriod(timePeriod)
	    	.setCreateDisposition(CreateDisposition.CREATE_IF_NEEDED)
	    	.setWriteDisposition(WriteDisposition.WRITE_TRUNCATE);
    
	    dup.apply();
	    
	    // wait for this pipeline to finish running since it outputs tables that are read in later
		DataflowPipelineJob resultUpdate = (DataflowPipelineJob) pipe.run();
		resultUpdate.waitToFinish(-1, TimeUnit.MINUTES, new MonitoringUtil.PrintHandler(System.out));
		LOG.info("Update table job completed, with status: " + resultUpdate.getState().toString());

		// Pipeline 2:
		JSONObject downloadsConfig = dup.getDownloadsConfig();
		JSONObject uploadsConfig = dup.getUploadsConfig();
		
		UpdatePipelineOptions optionsMergeAndISP = options.cloneAs(UpdatePipelineOptions.class);
	    optionsMergeAndISP.setAppName("UpdatePipeline-MergeAndISP");
		Pipeline pipe2 = Pipeline.create(optionsMergeAndISP);

		// === merge the table (outputs a table and also returns the rows)
		MergeUploadDownloadPipeline mergeUploadDownload = new MergeUploadDownloadPipeline(pipe2);
		mergeUploadDownload.setDownloadTable((String) downloadsConfig.get("outputTable"))
			.setUploadTable((String) uploadsConfig.get("outputTable"))
			.setOutputTable((String) downloadsConfig.get("mergeTable"))
			.setWriteDisposition(WriteDisposition.WRITE_TRUNCATE)
			.setCreateDisposition(CreateDisposition.CREATE_IF_NEEDED);
		
		PCollection<TableRow> rows = mergeUploadDownload.apply();

		// === add ISPs
		rows = new AddISPsPipeline(pipe).apply(rows);

		// === add server locations and mlab site info
		rows = new AddMlabSitesInfoPipeline(pipe).apply(rows);
		
		// === merge ASNs
		rows = new MergeASNsPipeline(pipe).apply(rows);
		
		// === add local time
		rows = new AddLocalTimePipeline(pipe).apply(rows);
		
		// === clean locations (important to do before resolving location names)
		rows = new LocationCleaningPipeline(pipe).apply(rows);
					
		// === add location names
		rows = new AddLocationPipeline(pipe).apply(rows);
		
		// write to the final table
		BigQueryIOHelpers.writeTable(rows, (String) downloadsConfig.get("withISPTable"), 
				Schema.fromJSONFile((String) downloadsConfig.get("withISPTableSchema")),
				WriteDisposition.WRITE_TRUNCATE, CreateDisposition.CREATE_IF_NEEDED);
		
		// run the pipeline
		DataflowPipelineJob resultsMergeAndISPs = (DataflowPipelineJob) pipe2.run();
		
		// wait for the pipeline to finish
		resultsMergeAndISPs.waitToFinish(-1, TimeUnit.MINUTES, new MonitoringUtil.PrintHandler(System.out));
		
		LOG.info("Merge + ISPs job completed, with status: " + resultsMergeAndISPs.getState().toString());
	}
}
