package au.org.ala.volunteer

import org.apache.commons.lang3.StringUtils

class FieldSyncService {

    static transactional = true

    def logService
    def fullTextIndexService

    Map retrieveFieldsForTask(Task taskInstance) {
        Map recordValues = new LinkedHashMap()
        taskInstance?.fields?.each { field ->
            def recordMap = recordValues.get(field.recordIdx)
            if (recordMap == null) {
                recordMap = new LinkedHashMap()
                recordValues.put field.recordIdx, recordMap
            }
            if (!field.superceded) {
                recordMap.put field.name, field.value
            }
        }
        recordValues
    }

    boolean fieldValuesAreEqual(String a, String b) {
        String value1 = a ?: ""  // Normalize null to empty strings for the sake of comparison
        String value2 = b ?: ""
        return value1.equals(value2);
    }

    boolean isCollectionOrArray(object) {
        if (object == null) {
            return false
        }
        [Collection, Object[]].any { it.isAssignableFrom(object.getClass()) }
    }

    /** Duplicate values are an indication that there are multiple form fields with the exact same name, and the values are being collated into and array
     * This is generally bad, but may happen because templates are user modifiable. In a lot of cases the values may well be the same, so
     * we can coalesce them back into a single string value, otherwise we comma separate them in order to preserve their values for manual fix up later
     *
     * @param values
     * @return
     */
    def handleDuplicateFormFields(Task task, String fieldname, values) {
        def distinctValues = []
        values.each {
            if (StringUtils.isNotEmpty(it) && !distinctValues.contains(it)) {
                distinctValues << it
            }
        }

        // They are all null
        if (distinctValues.size() == 0) {
            return ""
        }
        if (distinctValues.size() ==  1) {
            return distinctValues[0]
        }

        def value = distinctValues.join(",")
        log.warn("WARNING: Duplicate field values detected for task: ${task?.id} field: ${fieldname} values: ${value}")
        return value
    }

    // TODO hard coded for expediency, replace with something more useful in future
    List<String> truncateFieldsForProject(Project project) {
        def result;
        switch (project.template.viewName) {
            case 'cameratrapTranscribe': result = ['vernacularName', 'certainty', 'unlisted']
                break
            default: result = []
        }
        return result
    }

    /**
     * Takes some new field values and sensibly syncs with existing field values
     * in the database.
     *
     * @param record
     * @param fieldValues
     * @return
     */
    void syncFields(Task task, Map fieldValues, String transcriberUserId, Boolean markAsFullyTranscribed, Boolean markAsFullyValidated, Boolean isValid, List<String> truncateFields = [], String userIp = null) {
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

                    def value = keyValue.value

                    if (isCollectionOrArray(value)) {
                        value = handleDuplicateFormFields(task, keyValue.key, value)
                    }

                    Field oldFieldValue = oldFieldValues.get(keyValue.key)
                    if (oldFieldValue != null) {

                        if (!fieldValuesAreEqual(oldFieldValue.value, value)) {
                            //if different users
                            if (oldFieldValue.transcribedByUserId != transcriberUserId) {
                                //just save it
                                Field field = new Field()
                                field.name = keyValue.key
                                field.value = value
                                field.transcribedByUserId = transcriberUserId
                                field.task = task
                                field.recordIdx = idx
                                field.updated = new Date()
                                field.save(flush: true)
                                if (field.hasErrors()) {
                                    field.errors.allErrors.each { log.error(it) }
                                    task.errors.reject(field.errors.toString())
                                    return;
                                }

                                //keep the original, but mark as superceded
                                oldFieldValue.superceded = true
                                oldFieldValue.updated = new Date()
                                oldFieldValue.save(flush: true)
                                if (oldFieldValue.hasErrors()) {
                                    oldFieldValue.errors.allErrors.each { log.error(it) }
                                    task.errors.reject(oldFieldValue.errors.toString())
                                    return;
                                }

                            } else {
                                //just replace the value
                                oldFieldValue.value = value
                                oldFieldValue.updated = new Date()
                                oldFieldValue.save(flush: true)

                                if (oldFieldValue.hasErrors()) {
                                    oldFieldValue.errors.allErrors.each { log.error(it) }
                                    task.errors.reject(oldFieldValue.errors.toString())
                                    return;
                                }
                            }
                        }

                    } else {
                        //persist these values
                        Field field = new Field(recordIdx: idx, name: keyValue.key, value: value,
                                task: task, transcribedByUserId: transcriberUserId, superceded: false)
                        field.save(flush: true)
                        if (field.hasErrors()) {
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

        // Slightly dodgy hack, as camera trap records can be removed on re-save or validation
        // and the record index is shared between selected images and unlisted write ins
        def sortedIndexes = fieldValues.keySet().findAll { StringUtils.isNumeric(it) }.collect { Integer.parseInt(it) }.sort().reverse()
        truncateFields.each { fieldName ->
            def truncIdx = maxIndexFor(fieldName, fieldValues, sortedIndexes)
            markSuperceded(task, truncIdx, fieldName)
        }

        def now = Calendar.instance.time;

        //set the transcribed by
        if (markAsFullyTranscribed) {
            // Only set it if it hasn't already been set. The rules are the first person to save gets the transcription
            if (!task.fullyTranscribedBy) {
                task.fullyTranscribedBy = transcriberUserId
                task.fullyTranscribedIpAddress = userIp
                def user = User.findByUserId(transcriberUserId)
                user.transcribedCount++
                user.save(flush: true)
            }
            if (!task.dateFullyTranscribed) {
                task.dateFullyTranscribed = now
            }
            if (!task.transcribedUUID) {
                task.transcribedUUID = UUID.randomUUID()
            }
        }

        if (markAsFullyValidated) {
            // Again, only update the validated user and date if it hasn't already been set.
            if (!task.fullyValidatedBy) {
                task.fullyValidatedBy = transcriberUserId
                def user = User.findByUserId(transcriberUserId)
                user.validatedCount++
                user.save(flush: true)
            }
            if (!task.dateFullyValidated) {
                task.dateFullyValidated = now
            }
            if (!task.validatedUUID) {
                task.validatedUUID = UUID.randomUUID()
            }
        }

        if (isValid != null) {
            task.isValid = isValid
        }

        task.dateLastUpdated = now
        task.viewed++; // increment view count

        task.save(flush: true, failOnError: true)

        // Should be dealt with by GORM event
        //DomainUpdateService.scheduleTaskIndex(task)
    }

    int maxIndexFor(fieldName, Map fieldValues, sortedIndexes) {

        for (def key : sortedIndexes) {
            if (fieldValues.get(key.toString())[fieldName]) {
                return key
            }
        }
        return -1;
//        def c = Field.createCriteria()
//        c.get {
//            eq('task', task)
//            eq('name', fieldName)
//            eq('superceded', false)
//            projections {
//                max('recordIdx')
//            }
//        }
    }

    void markSuperceded(Task theTask, int truncIdx, String fieldName) {
        Field.where {
            task == theTask && superceded == false && recordIdx > truncIdx && name == fieldName
        }.updateAll(superceded: true)
    }

}
