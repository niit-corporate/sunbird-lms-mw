/**
 * 
 */
package org.sunbird.learner.actors;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONException;
import org.json.JSONObject;
import org.sunbird.cassandra.CassandraOperation;
import org.sunbird.cassandraimpl.CassandraOperationImpl;
import org.sunbird.common.ElasticSearchUtil;
import org.sunbird.common.exception.ProjectCommonException;
import org.sunbird.common.models.response.Response;
import org.sunbird.common.models.util.ActorOperations;
import org.sunbird.common.models.util.HttpUtil;
import org.sunbird.common.models.util.JsonKey;
import org.sunbird.common.models.util.LogHelper;
import org.sunbird.common.models.util.ProjectUtil;
import org.sunbird.common.models.util.PropertiesCache;
import org.sunbird.common.responsecode.ResponseCode;
import org.sunbird.learner.util.Util;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import akka.actor.UntypedAbstractActor;

/**
 * This class will handle all the background job.
 * Example when ever course is published then this job will
 * collect course related data from EKStep and update with Sunbird.
 * @author Manzarul
 */
public class BackgroundJobManager extends UntypedAbstractActor{
	private static final LogHelper LOGGER = LogHelper.getInstance(BackgroundJobManager.class.getName());
	private static Map<String,String> headerMap = new HashMap<String, String>();
	 private CassandraOperation cassandraOperation = new CassandraOperationImpl();
     private static Util.DbInfo dbInfo = null;
	 static{
		 headerMap.put("content-type", "application/json");
		 headerMap.put("accept", "application/json");
      }
	@Override
	public void onReceive(Object message) throws Throwable {
		
        if (message instanceof Response) {
        	LOGGER.info("BackgroundJobManager  onReceive called");
        	if(dbInfo==null)
            dbInfo=Util.dbInfoMap.get(JsonKey.COURSE_MANAGEMENT_DB);
        	
            Response actorMessage = (Response) message;
            String requestedOperation = (String) actorMessage.get(JsonKey.OPERATION);
            if (requestedOperation.equalsIgnoreCase(ActorOperations.PUBLISH_COURSE.getValue())) {
            	manageBackgroundJob(((Response) actorMessage).getResult());

            }else if(requestedOperation.equalsIgnoreCase(ActorOperations.UPDATE_USER_COUNT.getValue())){
                updateUserCount(actorMessage);

            }else {
            	LOGGER.info("UNSUPPORTED OPERATION");
                ProjectCommonException exception = new ProjectCommonException(ResponseCode.invalidOperationName.getErrorCode(), ResponseCode.invalidOperationName.getErrorMessage(), ResponseCode.CLIENT_ERROR.getResponseCode());
                sender().tell(exception, self());
            }
        } else {
        	LOGGER.info("UNSUPPORTED MESSAGE FOR BACKGROUND JOB MANAGER");
        }
    	
	
	}
	
   /**
    * Method to update the user count .
    * @param actorMessage
    */
   @SuppressWarnings("unchecked")
   private void updateUserCount(Response actorMessage) {
		String courseId = (String) actorMessage.get(JsonKey.COURSE_ID);
		Map<String, Object> updateRequestMap = actorMessage.getResult();
		
		Response result = cassandraOperation.getPropertiesValueById(dbInfo.getKeySpace(), dbInfo.getTableName(), courseId, JsonKey.USER_COUNT);
		 Map<String,Object> responseMap = null;
		if(null != (result.get(JsonKey.RESPONSE)) && ((List<Map<String, Object>>) result.get(JsonKey.RESPONSE)).size() > 0){
		 responseMap = ((List<Map<String, Object>>) result.get(JsonKey.RESPONSE)).get(0);
		}
		int userCount = (int) (responseMap.get(JsonKey.USER_COUNT) != null ? responseMap.get(JsonKey.USER_COUNT) : 0);
		updateRequestMap.put(JsonKey.USER_COUNT, userCount+1);
		updateRequestMap.put(JsonKey.ID,courseId);
		updateRequestMap.remove(JsonKey.OPERATION);
		updateRequestMap.remove(JsonKey.COURSE_ID);
		Response resposne = cassandraOperation.updateRecord(dbInfo.getKeySpace(), dbInfo.getTableName(),updateRequestMap);
		if(resposne.get(JsonKey.RESPONSE).equals(JsonKey.SUCCESS)){
			LOGGER.info("USER COUNT UPDATED SUCCESSFULLY IN COURSE MGMT TABLE");
		}else{
			LOGGER.info("USER COUNT NOT UPDATED SUCCESSFULLY IN COURSE MGMT TABLE");
		}
		
	}

/**
    * 
    * @param data Map<String, Object>
    * @return boolean
    */
	@SuppressWarnings("unchecked")
	private boolean manageBackgroundJob(Map<String, Object> data) {
		if (data == null)
			return false;
			List<Map<String,Object>> list = (List<Map<String, Object>>) data.get(JsonKey.RESPONSE);
			Map<String,Object> content = list.get(0);
			String contentId = (String) content.get(JsonKey.CONTENT_ID);
			if (!ProjectUtil.isStringNullOREmpty(contentId)) {
				String contentData = getCourseData(contentId);
				if (!ProjectUtil.isStringNullOREmpty(contentData)) {
					Map<String, Object> map = getContentDetails(contentData);
					map.put(JsonKey.ID, (String) content.get(JsonKey.COURSE_ID));
					updateCourseManagement(map);
					List<String> createdForValue = null;
					Object obj = content.get(JsonKey.COURSE_CREATED_FOR);
					if (obj != null) {
						createdForValue = (List<String>) obj;
					}
					content.remove(JsonKey.COURSE_CREATED_FOR);
					content.put(JsonKey.APPLICABLE_FOR, createdForValue);
					Map<String,Object> finalResponseMap = (Map<String,Object>)map.get(JsonKey.RESULT);
					finalResponseMap.putAll(content);
					finalResponseMap.put(JsonKey.OBJECT_TYPE, ProjectUtil.EsType.course.getTypeName());
					cacheCourseData(ProjectUtil.EsIndex.sunbird.getIndexName(), ProjectUtil.EsType.course.getTypeName(),
							(String) map.get(JsonKey.ID), finalResponseMap);
				}
			}
		return true;
	}
	
	/**
	 * Method to get the course data.
	 * @param contnetId String
	 * @return String
	 */
	private String getCourseData(String contnetId) {
		String responseData = null;
		try {
			responseData = HttpUtil.sendGetRequest(PropertiesCache.getInstance().getProperty(JsonKey.EKSTEP_CONTNET_URL) + contnetId, headerMap);
		} catch (IOException e) {
			LOGGER.error(e);
		}
		return responseData;
	}

	/**
	 * Method to get the content details of the given content id.
	 * @param content String
	 * @return Map<String, Object>
	 */
	@SuppressWarnings("unchecked")
	private Map<String, Object> getContentDetails(String content) {
		Map<String, Object> map = new HashMap<>();
		ObjectMapper mapper = new ObjectMapper();
		try {
			JSONObject object = new JSONObject(content);
			JSONObject resultObj = object.getJSONObject(JsonKey.RESULT);
			HashMap<String,Map<String,Object>> result =
					mapper.readValue(resultObj.toString(), HashMap.class);
			Map<String,Object> contentMap = result.get(JsonKey.CONTENT);
			map.put(JsonKey.APPICON, contentMap.get(JsonKey.APPICON));
			try{
			  map.put(JsonKey.TOC_URL, contentMap.get(JsonKey.TOC_URL));
			}catch(Exception e){
				LOGGER.error(e);
			}
			map.put(JsonKey.COUNT, contentMap.get(JsonKey.LEAF_NODE_COUNT));
			/*List<Map<String,Object>> children = (List<Map<String, Object>>) contentMap.get(JsonKey.CHILDREN);
			int size = 0;
			if (children != null) {
				size = getChildCount(children);
				map.put(JsonKey.COUNT, size);
			}*/
			map.put(JsonKey.RESULT, contentMap);
			
		} catch (JSONException e) {
			LOGGER.error(e);
		} catch (JsonParseException e) {
			LOGGER.error(e);
		} catch (JsonMappingException e) {
			LOGGER.error(e);
		} catch (IOException e) {
			LOGGER.error(e);
		} 
		return map;
	}
	
	/**
	 * Method to update the course management data on basis of course id.
	 * @param data Map<String, Object>
	 * @return boolean
	 */
	private boolean updateCourseManagement(Map<String, Object> data) {
		Map<String, Object> updateRequestMap = new HashMap<>();
		updateRequestMap.put(JsonKey.NO_OF_LECTURES, data.get(JsonKey.COUNT) != null ? data.get(JsonKey.COUNT) : 0);
		updateRequestMap.put(JsonKey.COURSE_LOGO_URL,
				data.get(JsonKey.APPICON) != null ? data.get(JsonKey.APPICON) : "");
		updateRequestMap.put(JsonKey.TOC_URL, data.get(JsonKey.TOC_URL) != null ? data.get(JsonKey.TOC_URL) : "");
		updateRequestMap.put(JsonKey.ID, (String)data.get(JsonKey.ID));
		Response resposne = cassandraOperation.updateRecord(dbInfo.getKeySpace(), dbInfo.getTableName(),
				updateRequestMap);
		LOGGER.info(resposne.toString());
		if (!(resposne.get(JsonKey.RESPONSE) instanceof ProjectCommonException)) {
			return true;
		}
		return false;
	}
	
	/**
	 * Method to cache the course data .
	 * @param index String
	 * @param type String
	 * @param identifier String
	 * @param data Map<String,Object>
	 * @return boolean
	 */
	private boolean cacheCourseData(String index, String type, String identifier, Map<String,Object> data) {
		String response = ElasticSearchUtil.createData(index, type, identifier, data);
		if(!ProjectUtil.isStringNullOREmpty(response)) {
			return true;
		}
		LOGGER.info("unbale to save the data inside ES with identifier " + identifier);
		return false;
	}
	
	@SuppressWarnings("unchecked")
	private int getChildCount(List<Map<String, Object>> mapList) {
		int size = mapList.size();
		for (Map<String, Object> tmp : mapList) {
			Object obj = tmp.get(JsonKey.CHILDREN);
			if (obj != null && obj instanceof List) {
				List<Map<String, Object>> child1 = (List<Map<String, Object>>) obj;
				if (child1 != null && child1.size() > 1) {
					size = size + child1.size() - 1;
					for (Map<String, Object> innerChildmap : child1) {
						Object innerChildObj = innerChildmap.get(JsonKey.CHILDREN);
						if (innerChildObj != null && innerChildObj instanceof List) {
							List<Map<String, Object>> child2 = (List<Map<String, Object>>) innerChildObj;
							if (child2 != null && child2.size() > 1) {
								size = size + child2.size() - 1;
							}
						}
					}
				}
			}
		}
		return size;
	}
}