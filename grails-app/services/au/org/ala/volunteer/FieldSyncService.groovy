package au.org.ala.volunteer

import org.springframework.validation.Errors

class FieldSyncService {

  static transactional = true
  def serviceMethod() {}

  Map retrieveFieldsForTask(Task taskInstance){
    Map recordValues = new LinkedHashMap()
    taskInstance.fields.each { field ->
      def recordMap = recordValues.get(field.recordIdx)
      if(recordMap==null){
        recordMap = new LinkedHashMap()
        recordValues.put field.recordIdx, recordMap
      }
      recordMap.put field.name,field.value
    }
    log.debug("record values: " + recordValues )
    recordValues
  }

  /**
   * Takes some new field values and sensibly syncs with existing field values
   * in the database.
   *
   * @param record
   * @param fieldValues
   * @return
   */
  void syncFields(Task task, Map fieldValues, String transcriberUserId, boolean markAsFullyTranscribed, boolean markAsFullyValidated) {

    //sync
    def idx = 0
    def hasMore = true
    while (hasMore) {
      def fieldValuesForRecord = fieldValues.get(idx.toString())
      if (fieldValuesForRecord) {

        //get existing fields, and add to a map
        def oldFields = Field.executeQuery("from Field f where task = :task and recordIdx = :recordIdx and superceded = false",
                [task: task, recordIdx: idx])

        Map oldFieldValues = new LinkedHashMap()
        oldFields.each { field -> oldFieldValues.put(field.name, field) }

        fieldValuesForRecord.each { keyValue ->

          Field oldFieldValue = oldFieldValues.get(keyValue.key)
          if (oldFieldValue != null) {

            if (oldFieldValue.value != keyValue.value) {
              //if different users
              if (oldFieldValue.transcribedByUserId != transcriberUserId) {
                //just save it
                Field field = new Field()
                field.name = keyValue.key
                field.value = keyValue.value
                field.transcribedByUserId = transcriberUserId
                field.task = task
                field.updated = new Date()
                field.save(flush: true)
                if(field.hasErrors()) {
                  field.errors.allErrors.each { log.error(it) }
                  task.errors.reject(field.errors.toString())
                  return;
                }

                //keep the original, but mark as superceded
                oldFieldValue.superceded = true
                oldFieldValue.updated = new Date()
                oldFieldValue.save(flush: true)
                if(oldFieldValue.hasErrors()) {
                  oldFieldValue.errors.allErrors.each { log.error(it) }
                  task.errors.reject(oldFieldValue.errors.toString())
                  //task.errors.addAllErrors(oldFieldValue.errors)
                  return;
                }

              } else {
                //just replace the value
                oldFieldValue.value = keyValue.value
                oldFieldValue.updated = new Date()
                oldFieldValue.save(flush: true)

                if(oldFieldValue.hasErrors()) {
                  oldFieldValue.errors.allErrors.each { log.error(it) }
                  task.errors.reject(oldFieldValue.errors.toString())
                  //task.errors.addAllErrors(oldFieldValue.errors)
                  return;
                }
              }
            }

          } else {
            //persist these values
            Field field = new Field(recordIdx: idx, name: keyValue.key, value: keyValue.value,
                    task: task, transcribedByUserId: transcriberUserId, superceded: false)
            field.save(flush: true)
            if(field.hasErrors()) {
               field.errors.allErrors.each { log.error(it) }
               task.errors.reject(field.errors.toString())
               return;
            }
          }
        }
        idx = idx + 1
      } else {
        hasMore = false
      }
    }

    //set the transcribed by
    if(markAsFullyTranscribed){
      task.fullyTranscribedBy = transcriberUserId
    }

    if(markAsFullyValidated){
      task.fullyValidatedBy = transcriberUserId
    } else {
      //reset the fully validated flag
      task.fullyValidatedBy = null
    }

    task.save(flush:true)
  }
}