package org.sunbird.learner.actors.bulkupload;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.UntypedAbstractActor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.sunbird.cassandra.CassandraOperation;
import org.sunbird.cassandraimpl.CassandraOperationImpl;
import org.sunbird.common.exception.ProjectCommonException;
import org.sunbird.common.models.response.Response;
import org.sunbird.common.models.util.ActorOperations;
import org.sunbird.common.models.util.JsonKey;
import org.sunbird.common.models.util.ProjectLogger;
import org.sunbird.common.models.util.ProjectUtil;
import org.sunbird.common.models.util.datasecurity.OneWayHashing;
import org.sunbird.common.request.Request;
import org.sunbird.common.responsecode.ResponseCode;
import org.sunbird.learner.actors.BackgroundJobManager;
import org.sunbird.learner.util.Util;
import org.sunbird.learner.util.Util.DbInfo;
import org.sunbird.services.sso.SSOManager;
import org.sunbird.services.sso.impl.KeyCloakServiceImpl;

public class BulkUploadBackGroundJobActor extends UntypedAbstractActor {

  private ActorRef backGroundActorRef;

  public BulkUploadBackGroundJobActor() {
    backGroundActorRef = getContext().actorOf(Props.create(BackgroundJobManager.class), "backGroundActor");
   }
  private CassandraOperation cassandraOperation = new CassandraOperationImpl();
  private SSOManager ssoManager = new KeyCloakServiceImpl();
  @Override
  public void onReceive(Object message) throws Throwable {
    if (message instanceof Request) {
      try {
        ProjectLogger.log("BulkUploadBackGroundJobActor onReceive called");
        Request actorMessage = (Request) message;
        if (actorMessage.getOperation().equalsIgnoreCase(ActorOperations.PROCESS_BULK_UPLOAD.getValue())) {
          process(actorMessage);
        }else {
          ProjectLogger.log("UNSUPPORTED OPERATION");
          ProjectCommonException exception = new ProjectCommonException(
              ResponseCode.invalidOperationName.getErrorCode(),
              ResponseCode.invalidOperationName.getErrorMessage(),
              ResponseCode.CLIENT_ERROR.getResponseCode());
          sender().tell(exception, self());
        }
      } catch (Exception ex) {
        ProjectLogger.log(ex.getMessage(), ex);
        sender().tell(ex, self());
      }
    }else {
      // Throw exception as message body
      ProjectLogger.log("UNSUPPORTED MESSAGE");
      ProjectCommonException exception = new ProjectCommonException(
          ResponseCode.invalidRequestData.getErrorCode(),
          ResponseCode.invalidRequestData.getErrorMessage(),
          ResponseCode.CLIENT_ERROR.getResponseCode());
      sender().tell(exception, self());
    }
  }

  private void process(Request actorMessage) {
    ObjectMapper mapper = new ObjectMapper();
    String processId = (String) actorMessage.get(JsonKey.PROCESS_ID);
    Map<String,Object> dataMap = getBulkData(processId);
    TypeReference<List<Map<String,Object>>> mapType = new TypeReference<List<Map<String,Object>>>() {};
    List<Map<String,Object>> jsonList = null;
    try {
      jsonList = mapper.readValue((String)dataMap.get(JsonKey.DATA), mapType);
    } catch (IOException e) {
      ProjectLogger.log("Exception occurred while converting json String to List in BulkUploadBackGroundJobActor : ", e);
    }
    if(((String)dataMap.get(JsonKey.OBJECT_TYPE)).equalsIgnoreCase(JsonKey.USER)){
      processUserInfo(jsonList,processId);
    }
    
   }
  

  private void processUserInfo(List<Map<String, Object>> dataMapList, String processId) {

    Util.DbInfo usrDbInfo = Util.dbInfoMap.get(JsonKey.USER_DB);
    List<Map<String, Object>> failureUserReq = new ArrayList<>();
    List<Map<String, Object>> successUserReq = new ArrayList<>();
    Map<String,Object> userMap = null;
    for(int i = 0 ; i < dataMapList.size() ; i++){
      userMap = dataMapList.get(i);
      String errMsg = validateUser(userMap);
      if(errMsg.equalsIgnoreCase(JsonKey.SUCCESS)){
        try{

          if ( null != userMap.get(JsonKey.ROLES)) {
            String[] userRole = ((String) userMap.get(JsonKey.ROLES)).split(",");
            userMap.put(JsonKey.ROLES, userRole);
          } 
          
          userMap = insertRecordToKeyCloak(userMap);
          Map<String,Object> tempMap = new HashMap<>();
          tempMap.putAll(userMap);
          tempMap.remove(JsonKey.EMAIL_VERIFIED);
          tempMap.remove(JsonKey.PHONE_VERIFIED);
          Response response = null;
          try {
            response = cassandraOperation
                .insertRecord(usrDbInfo.getKeySpace(), usrDbInfo.getTableName(), tempMap);
          } finally {
            if (null == response) {
              ssoManager.removeUser(userMap);
            }
          }
          //save successfully created user data 
          tempMap.putAll(userMap);
          tempMap.remove(JsonKey.STATUS);
          tempMap.remove(JsonKey.CREATED_DATE);
          tempMap.remove(JsonKey.CREATED_BY);
          tempMap.remove(JsonKey.ID);
          successUserReq.add(tempMap);
          //insert details to user_org table
          insertRecordToUserOrgTable(userMap);
          //insert details to user Ext Identity table
          userMap.put(JsonKey.PHONE_NUMBER_VERIFIED,userMap.get(JsonKey.PHONE_VERIFIED));
          insertRecordToUserExtTable(userMap);
          //update elastic search
          Response usrResponse = new Response();
          usrResponse.getResult()
              .put(JsonKey.OPERATION, ActorOperations.UPDATE_USER_INFO_ELASTIC.getValue());
          usrResponse.getResult().put(JsonKey.ID, userMap.get(JsonKey.ID));
          ProjectLogger.log("making a call to save user data to ES in BulkUploadBackGroundJobActor");
          backGroundActorRef.tell(usrResponse,self());
            
        } catch(Exception ex) {
          ProjectLogger.log("Exception occurred while bulk user upload in BulkUploadBackGroundJobActor:", ex);
          userMap.remove(JsonKey.ID);
          userMap.put(JsonKey.ERROR_MSG, ex.getMessage());
          failureUserReq.add(userMap);
        }
      }else{
        userMap.put(JsonKey.ERROR_MSG, errMsg);
        failureUserReq.add(userMap);
      }
     }
    //Insert record to BulkDb table
    Map<String,Object> map = new HashMap<>();
    map.put(JsonKey.ID, processId);
    map.put(JsonKey.SUCCESS_RESULT, convertMapToJsonString(successUserReq));
    map.put(JsonKey.FAILURE_RESULT, convertMapToJsonString(failureUserReq));
    map.put(JsonKey.PROCESS_END_TIME, ProjectUtil.getFormattedDate());
    Util.DbInfo  bulkDb = Util.dbInfoMap.get(JsonKey.BULK_OP_DB);
    try{
    cassandraOperation.updateRecord(bulkDb.getKeySpace(), bulkDb.getTableName(), map);
    }catch(Exception e){
      ProjectLogger.log("Exception Occurred while updating bulk_upload_process in BulkUploadBackGroundJobActor : ", e);
    }
  }

  private String convertMapToJsonString(List<Map<String, Object>> mapList) {
    ObjectMapper mapper = new ObjectMapper();
    try {
      return mapper.writeValueAsString(mapList);
    } catch (IOException e) {
      ProjectLogger.log(e.getMessage(), e);
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> getBulkData(String processId) {
    Util.DbInfo  bulkDb = Util.dbInfoMap.get(JsonKey.BULK_OP_DB);
    try{
      Map<String,Object> map = new HashMap<>();
      map.put(JsonKey.ID, processId);
      map.put(JsonKey.PROCESS_START_TIME, ProjectUtil.getFormattedDate());
      map.put(JsonKey.STATUS, ProjectUtil.BulkProcessStatus.IN_PROGRESS.getValue());
      cassandraOperation.updateRecord(bulkDb.getKeySpace(), bulkDb.getTableName(), map);
    }catch(Exception ex){
      ProjectLogger.log("Exception occurred while updating status to bulk_upload_process "
          + "table in BulkUploadBackGroundJobActor.", ex);
    }
    Response res = cassandraOperation.getRecordById(bulkDb.getKeySpace(), bulkDb.getTableName(), processId);
    return (((List<Map<String,Object>>)res.get(JsonKey.RESPONSE)).get(0));
  }

  private void insertRecordToUserExtTable(Map<String, Object> requestMap) {
    Util.DbInfo usrExtIdDb = Util.dbInfoMap.get(JsonKey.USR_EXT_ID_DB);
    Map<String, Object> map = new HashMap<>();
    Map<String, Object> reqMap = new HashMap<>();
    reqMap.put(JsonKey.USER_ID, requestMap.get(JsonKey.USER_ID));
      /* update table for userName,phone,email,Aadhar No
       * for each of these parameter insert a record into db
       * for username update isVerified as true
       * and for others param this will be false
       * once verified will update this flag to true
       */

    map.put(JsonKey.USER_ID, requestMap.get(JsonKey.ID));
    map.put(JsonKey.IS_VERIFIED, false);
    if (requestMap.containsKey(JsonKey.USERNAME) && !(ProjectUtil
        .isStringNullOREmpty((String) requestMap.get(JsonKey.USERNAME)))) {
      map.put(JsonKey.ID, ProjectUtil.getUniqueIdFromTimestamp(1));
      map.put(JsonKey.EXTERNAL_ID, requestMap.get(JsonKey.USERNAME));
      map.put(JsonKey.EXTERNAL_ID_VALUE, JsonKey.USERNAME);
      map.put(JsonKey.IS_VERIFIED, true);

      reqMap.put(JsonKey.EXTERNAL_ID_VALUE, requestMap.get(JsonKey.USERNAME));

      updateUserExtIdentity(map, usrExtIdDb);
    }
    if (requestMap.containsKey(JsonKey.PHONE) && !(ProjectUtil
        .isStringNullOREmpty((String) requestMap.get(JsonKey.PHONE)))) {
      map.put(JsonKey.ID, ProjectUtil.getUniqueIdFromTimestamp(1));
      map.put(JsonKey.EXTERNAL_ID, JsonKey.PHONE);
      map.put(JsonKey.EXTERNAL_ID_VALUE, requestMap.get(JsonKey.PHONE));

      if (null != (requestMap.get(JsonKey.PHONE_NUMBER_VERIFIED))
          &&
          (boolean) requestMap.get(JsonKey.PHONE_NUMBER_VERIFIED)) {
        map.put(JsonKey.IS_VERIFIED, true);
      }
      reqMap.put(JsonKey.EXTERNAL_ID_VALUE, requestMap.get(JsonKey.PHONE));

      updateUserExtIdentity(map, usrExtIdDb);
    }
    if (requestMap.containsKey(JsonKey.EMAIL) && !(ProjectUtil
        .isStringNullOREmpty((String) requestMap.get(JsonKey.EMAIL)))) {
      map.put(JsonKey.ID, ProjectUtil.getUniqueIdFromTimestamp(1));
      map.put(JsonKey.EXTERNAL_ID, JsonKey.EMAIL);
      map.put(JsonKey.EXTERNAL_ID_VALUE, requestMap.get(JsonKey.EMAIL));

      if (null != (requestMap.get(JsonKey.EMAIL_VERIFIED)) &&
          (boolean) requestMap.get(JsonKey.EMAIL_VERIFIED)) {
        map.put(JsonKey.IS_VERIFIED, true);
      }
      reqMap.put(JsonKey.EXTERNAL_ID, requestMap.get(JsonKey.EMAIL));

      updateUserExtIdentity(map, usrExtIdDb);
    }
    if (requestMap.containsKey(JsonKey.AADHAAR_NO) && !(ProjectUtil
        .isStringNullOREmpty((String) requestMap.get(JsonKey.AADHAAR_NO)))) {
      map.put(JsonKey.ID, ProjectUtil.getUniqueIdFromTimestamp(1));
      map.put(JsonKey.EXTERNAL_ID, JsonKey.AADHAAR_NO);
      map.put(JsonKey.EXTERNAL_ID_VALUE, requestMap.get(JsonKey.AADHAAR_NO));

      reqMap.put(JsonKey.EXTERNAL_ID_VALUE, requestMap.get(JsonKey.AADHAAR_NO));

      updateUserExtIdentity(map, usrExtIdDb);
    }
  }
  
  private void updateUserExtIdentity(Map<String, Object> map, DbInfo usrExtIdDb) {
    try {
      cassandraOperation.insertRecord(usrExtIdDb.getKeySpace(), usrExtIdDb.getTableName(), map);
    } catch (Exception e) {
      ProjectLogger.log(e.getMessage(), e);
    }

  }

  private void insertRecordToUserOrgTable(Map<String, Object> userMap) {
    Util.DbInfo usrOrgDb = Util.dbInfoMap.get(JsonKey.USR_ORG_DB);
    Map<String, Object> reqMap = new HashMap<>();
    reqMap.put(JsonKey.ID, ProjectUtil.getUniqueIdFromTimestamp(1));
    reqMap.put(JsonKey.USER_ID, userMap.get(JsonKey.ID));
    reqMap.put(JsonKey.ORGANISATION_ID, userMap.get(JsonKey.REGISTERED_ORG_ID));
    reqMap.put(JsonKey.ORG_JOIN_DATE, ProjectUtil.getFormattedDate());
    List<String> roleList = new ArrayList<>();
    roleList.add(ProjectUtil.UserRole.CONTENT_CREATOR.getValue());
    reqMap.put(JsonKey.ROLES, roleList);

    try {
      cassandraOperation.insertRecord(usrOrgDb.getKeySpace(), usrOrgDb.getTableName(), reqMap);
    } catch (Exception e) {
      ProjectLogger.log(e.getMessage(), e);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> insertRecordToKeyCloak(Map<String, Object> userMap) {
    
    Util.DbInfo usrDbInfo = Util.dbInfoMap.get(JsonKey.USER_DB);
    
    if (userMap.containsKey(JsonKey.PROVIDER) && 
        !ProjectUtil.isStringNullOREmpty((String)userMap.get(JsonKey.PROVIDER))) {
      userMap.put(JsonKey.LOGIN_ID, 
          (String)userMap.get(JsonKey.USERNAME)+"@"+(String)userMap.get(JsonKey.PROVIDER));
    } else {
      userMap.put(JsonKey.LOGIN_ID,userMap.get(JsonKey.USERNAME));
    }
   
    if (null != userMap.get(JsonKey.LOGIN_ID)) {
      String loginId = (String) userMap.get(JsonKey.LOGIN_ID);
      Response resultFrUserName = cassandraOperation.getRecordsByProperty(usrDbInfo.getKeySpace(),
          usrDbInfo.getTableName(), JsonKey.LOGIN_ID, loginId);
      if (!(((List<Map<String, Object>>) resultFrUserName.get(JsonKey.RESPONSE)).isEmpty())) {
        throw new ProjectCommonException(
            ResponseCode.userAlreadyExist.getErrorCode(),
            ResponseCode.userAlreadyExist.getErrorMessage(),
            ResponseCode.SERVER_ERROR.getResponseCode());
      }
    }
    
      try {
        String userId = ssoManager.createUser(userMap);
        if (!ProjectUtil.isStringNullOREmpty(userId)) {
          userMap.put(JsonKey.USER_ID, userId);
          userMap.put(JsonKey.ID, userId);
        } else {
          throw new ProjectCommonException(
              ResponseCode.userRegUnSuccessfull.getErrorCode(),
              ResponseCode.userRegUnSuccessfull.getErrorMessage(),
              ResponseCode.SERVER_ERROR.getResponseCode());
        }
      } catch (Exception exception) {
        ProjectLogger.log("Exception occured while creating user in keycloak ", exception);
        throw exception;
      }
      
      userMap.put(JsonKey.CREATED_DATE, ProjectUtil.getFormattedDate());
      userMap.put(JsonKey.STATUS, ProjectUtil.Status.ACTIVE.getValue());
      if (ProjectUtil.isStringNullOREmpty((String) userMap.get(JsonKey.ROOT_ORG_ID))) {
        userMap.put(JsonKey.ROOT_ORG_ID, JsonKey.DEFAULT_ROOT_ORG_ID);
      }
      if (!ProjectUtil.isStringNullOREmpty((String) userMap.get(JsonKey.PASSWORD))) {
        userMap
            .put(JsonKey.PASSWORD, OneWayHashing.encryptVal((String) userMap.get(JsonKey.PASSWORD)));
      }
      /**
       * set role as PUBLIC by default if role is empty in request body.
       * And if roles are coming in request body, then check for PUBLIC role , if not
       * present then add PUBLIC role to the list
       *
       */

      if (userMap.containsKey(JsonKey.ROLES)) {
        String[] roleList = (String[]) userMap.get(JsonKey.ROLES);
        List<String> roles = new ArrayList<>(Arrays.asList(roleList));
        if (!roles.contains(ProjectUtil.UserRole.PUBLIC.getValue())) {
          roles.add(ProjectUtil.UserRole.PUBLIC.getValue());
          userMap.put(JsonKey.ROLES, roles);
        }
      } else {
        List<String> roles = new ArrayList<>();
        roles.add(ProjectUtil.UserRole.PUBLIC.getValue());
        userMap.put(JsonKey.ROLES, roles);
      }

      return userMap;
    }

  private String validateUser(Map<String,Object> map) {
    if (map.get(JsonKey.USERNAME) == null) {
        return ResponseCode.userNameRequired.getErrorMessage();
    }
    if (map.get(JsonKey.FIRST_NAME) == null
            || (ProjectUtil.isStringNullOREmpty((String) map.get(JsonKey.FIRST_NAME)))) {
      return ResponseCode.firstNameRequired.getErrorMessage();
    }  
    if (!(ProjectUtil.isStringNullOREmpty((String)map.get(JsonKey.EMAIL))) && !ProjectUtil.isEmailvalid((String) map.get(JsonKey.EMAIL))) {
      return ResponseCode.emailFormatError.getErrorMessage();
    }
    if(!ProjectUtil.isStringNullOREmpty((String)map.get(JsonKey.PHONE_VERIFIED))){
      try{
        map.put(JsonKey.PHONE_VERIFIED, Boolean.parseBoolean((String)map.get(JsonKey.PHONE_VERIFIED)));
      }catch(Exception ex){
        return "property phoneVerified should be instanceOf type Boolean.";
      }
    }
    if(!ProjectUtil.isStringNullOREmpty((String)map.get(JsonKey.EMAIL_VERIFIED))){
      try{
        map.put(JsonKey.EMAIL_VERIFIED, Boolean.parseBoolean((String)map.get(JsonKey.EMAIL_VERIFIED)));
      }catch(Exception ex){
        return "property emailVerified should be instanceOf type Boolean.";
      }
    }
    if(!ProjectUtil.isStringNullOREmpty((String) map.get(JsonKey.PROVIDER))){
      if(!ProjectUtil.isStringNullOREmpty((String) map.get(JsonKey.PHONE))){
          if(null != map.get(JsonKey.PHONE_VERIFIED)){
            if(map.get(JsonKey.PHONE_VERIFIED) instanceof Boolean){
              if(!((boolean) map.get(JsonKey.PHONE_VERIFIED))){
                return ResponseCode.phoneVerifiedError.getErrorMessage();
              }
            }else{
              return "property phoneVerified should be instanceOf type Boolean.";
            }
          }else{
            return ResponseCode.phoneVerifiedError.getErrorMessage();
          }
        }
      if(null != map.get(JsonKey.EMAIL_VERIFIED)){
        if(map.get(JsonKey.EMAIL_VERIFIED) instanceof Boolean){
          if(!((boolean) map.get(JsonKey.EMAIL_VERIFIED))){
            return ResponseCode.emailVerifiedError.getErrorMessage();
          }
        }else{
          return "property emailVerified should be instanceOf type Boolean.";
        }
      }else{
        return ResponseCode.emailVerifiedError.getErrorMessage();
      } 
    }
    
    return JsonKey.SUCCESS;
  }

}
