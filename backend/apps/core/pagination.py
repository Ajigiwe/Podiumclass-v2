from rest_framework.pagination import PageNumberPagination
from rest_framework.response import Response

class DocumentPagination(PageNumberPagination):
    """
    Pagination class that formats responses to comply with the Document Object format.
    """
    page_size = 25
    page_size_query_param = 'limit'
    max_page_size = 100

    def get_paginated_response(self, data):
        total_pages = self.page.paginator.num_pages if self.page else 1
        return Response({
            'count': self.page.paginator.count,
            'page': self.page.number,
            'total_pages': total_pages,
            'next': self.get_next_link(),
            'previous': self.get_previous_link(),
            'results': data
        })
