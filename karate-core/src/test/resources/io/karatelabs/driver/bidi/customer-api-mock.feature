@ignore
Feature: Customer API used by the BiDi interception demo

  Scenario: pathMatches('/api/customers/{id}') && paramValue('status') == 'active' && paramValue('include') == 'orders'
    * def response =
      """
      {
        id: '#(pathParams.id)',
        name: 'Ada Lovelace',
        status: 'ACTIVE',
        receivedStatus: '#(paramValue("status"))',
        receivedInclude: '#(paramValue("include"))',
        source: 'Karate feature mock'
      }
      """

  Scenario: pathMatches('/api/customers/{id}')
    * def responseStatus = 422
    * def response = { error: 'unsupported customer query', id: '#(pathParams.id)' }
