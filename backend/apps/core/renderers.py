from rest_framework.renderers import JSONRenderer

class DocumentJSONRenderer(JSONRenderer):
    """
    Standardizes ALL API responses into the Document Object structure.
    
    Single resource:
      { "document": { "id": "...", ... } }
      
    Collections / Lists:
      { "documents": [ ... ], "pagination": { ... } }
      
    Errors:
      { "error": { "code": "...", "message": "...", "details": { ... } } }
    """
    charset = 'utf-8'

    def render(self, data, accepted_media_type=None, renderer_context=None):
        response = renderer_context.get('response') if renderer_context else None

        # 1. Error Responses (HTTP 4xx / 5xx)
        if response and response.status_code >= 400:
            if isinstance(data, dict) and "error" in data:
                formatted_data = data
            elif isinstance(data, dict) and "detail" in data:
                formatted_data = {
                    "error": {
                        "code": data.get("code", "REQUEST_FAILED"),
                        "message": str(data.get("detail")),
                        "status": response.status_code
                    }
                }
            elif isinstance(data, dict):
                formatted_data = {
                    "error": {
                        "code": data.get("code", "VALIDATION_ERROR"),
                        "message": data.get("message", "Invalid input provided."),
                        "details": data.get("details", data),
                        "status": response.status_code
                    }
                }
            else:
                formatted_data = {
                    "error": {
                        "code": "ERROR",
                        "message": str(data),
                        "status": response.status_code
                    }
                }
            return super().render(formatted_data, accepted_media_type, renderer_context)

        # 2. Paginated Responses from DRF PageNumberPagination
        if isinstance(data, dict) and "results" in data:
            formatted_data = {
                "documents": data.get("results", []),
                "pagination": {
                    "count": data.get("count", 0),
                    "page": data.get("page", 1),
                    "totalPages": data.get("total_pages", 1),
                    "next": data.get("next"),
                    "previous": data.get("previous"),
                }
            }
            if "meta" in data:
                formatted_data["meta"] = data["meta"]
            return super().render(formatted_data, accepted_media_type, renderer_context)

        # 3. Explicit Document/Documents payloads (already formatted)
        if isinstance(data, dict) and ("document" in data or "documents" in data):
            return super().render(data, accepted_media_type, renderer_context)

        # 4. Standard List of resources -> wrap in documents
        if isinstance(data, list):
            formatted_data = {"documents": data}
            return super().render(formatted_data, accepted_media_type, renderer_context)

        # 5. Empty or None response
        if data is None or data == '':
            formatted_data = {"document": None}
            return super().render(formatted_data, accepted_media_type, renderer_context)

        # 6. Single resource dict or primitive -> wrap in document
        if isinstance(data, dict):
            formatted_data = {"document": data}
        else:
            formatted_data = {"document": {"value": data}}

        return super().render(formatted_data, accepted_media_type, renderer_context)
