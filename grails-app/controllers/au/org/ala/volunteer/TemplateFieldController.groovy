package au.org.ala.volunteer

class TemplateFieldController {

    static allowedMethods = [save: "POST", update: "POST", delete: "POST"]

    def index = {
        redirect(action: "list", params: params)
    }

    def list = {
        params.max = Math.min(params.max ? params.int('max') : 10, 100)
        [templateFieldInstanceList: TemplateField.list(params), templateFieldInstanceTotal: TemplateField.count()]
    }

    def create = {
        def templateFieldInstance = new TemplateField()
        templateFieldInstance.properties = params
        return [templateFieldInstance: templateFieldInstance]
    }

    def save = {
        def templateFieldInstance = new TemplateField(params)
        if (templateFieldInstance.save(flush: true)) {
            flash.message = "${message(code: 'default.created.message', args: [message(code: 'templateField.label', default: 'TemplateField'), templateFieldInstance.id])}"
            redirect(action: "show", id: templateFieldInstance.id)
        }
        else {
            render(view: "create", model: [templateFieldInstance: templateFieldInstance])
        }
    }

    def show = {
        def templateFieldInstance = TemplateField.get(params.id)
        if (!templateFieldInstance) {
            flash.message = "${message(code: 'default.not.found.message', args: [message(code: 'templateField.label', default: 'TemplateField'), params.id])}"
            redirect(action: "list")
        }
        else {
            [templateFieldInstance: templateFieldInstance]
        }
    }

    def edit = {
        def templateFieldInstance = TemplateField.get(params.id)
        if (!templateFieldInstance) {
            flash.message = "${message(code: 'default.not.found.message', args: [message(code: 'templateField.label', default: 'TemplateField'), params.id])}"
            redirect(action: "list")
        }
        else {
            return [templateFieldInstance: templateFieldInstance]
        }
    }

    def update = {
        def templateFieldInstance = TemplateField.get(params.id)
        if (templateFieldInstance) {
            if (params.version) {
                def version = params.version.toLong()
                if (templateFieldInstance.version > version) {
                    
                    templateFieldInstance.errors.rejectValue("version", "default.optimistic.locking.failure", [message(code: 'templateField.label', default: 'TemplateField')] as Object[], "Another user has updated this TemplateField while you were editing")
                    render(view: "edit", model: [templateFieldInstance: templateFieldInstance])
                    return
                }
            }
            templateFieldInstance.properties = params
            if (!templateFieldInstance.hasErrors() && templateFieldInstance.save(flush: true)) {
                flash.message = "${message(code: 'default.updated.message', args: [message(code: 'templateField.label', default: 'TemplateField'), templateFieldInstance.id])}"
                redirect(action: "show", id: templateFieldInstance.id)
            }
            else {
                render(view: "edit", model: [templateFieldInstance: templateFieldInstance])
            }
        }
        else {
            flash.message = "${message(code: 'default.not.found.message', args: [message(code: 'templateField.label', default: 'TemplateField'), params.id])}"
            redirect(action: "list")
        }
    }

    def delete = {
        def templateFieldInstance = TemplateField.get(params.id)
        if (templateFieldInstance) {
            try {
                templateFieldInstance.delete(flush: true)
                flash.message = "${message(code: 'default.deleted.message', args: [message(code: 'templateField.label', default: 'TemplateField'), params.id])}"
                redirect(action: "list")
            }
            catch (org.springframework.dao.DataIntegrityViolationException e) {
                flash.message = "${message(code: 'default.not.deleted.message', args: [message(code: 'templateField.label', default: 'TemplateField'), params.id])}"
                redirect(action: "show", id: params.id)
            }
        }
        else {
            flash.message = "${message(code: 'default.not.found.message', args: [message(code: 'templateField.label', default: 'TemplateField'), params.id])}"
            redirect(action: "list")
        }
    }
}
