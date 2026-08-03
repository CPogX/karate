@bidi
Feature: WebDriver BiDi against a real Selenium Grid browser

  Background:
    * def gridUrl = karate.properties['karate.bidi.gridUrl']
    * def browserName = karate.properties['karate.bidi.browserName']
    * def serverUrl = karate.properties['karate.bidi.serverUrl']
    * configure driver = { type: 'bidi', browserName: '#(browserName)', webDriverUrl: '#(gridUrl)', timeout: 15000 }
    * configure afterScenario = function(){ if (typeof driver !== 'undefined' && driver) { try { screenshot() } finally { try { driver.stopIntercept() } catch(e) {} driver.quit() } } }
    * driver 'about:blank'

  Scenario: delegate a real path-and-query API request to a Karate feature mock
    * driver.intercept({ patterns: [{ urlPattern: '*/api/customers/*' }], mock: 'classpath:io/karatelabs/driver/bidi/customer-api-mock.feature' })
    * driver serverUrl + '/bidi-customer'
    * match text('h1') == 'Customer Lookup'
    * input('#customer-id', '42')
    * select('#status', 'active')
    * select('#include', 'orders')
    * click('#load-customer')
    * waitForText('#customer-name', 'Ada Lovelace')
    * match text('#status-badge') == 'ACTIVE'
    * match text('#path-id') == '42'
    * match text('#status-query') == 'active'
    * match text('#include-query') == 'orders'
    * match text('#data-source') == 'Karate feature mock'
    * match text('#error') == ''
    * screenshot()

  Scenario: prompt events and PDF printing work on a real page
    * driver serverUrl + '/dialog'
    * match text('h1') == 'Dialog Test Page'
    * def handler = function(d) { d.accept('Grace Hopper') }
    * onDialog(handler)
    * click('#prompt-btn')
    * waitForText('#result', 'Prompt result: Grace Hopper')
    * match text('#result') == 'Prompt result: Grace Hopper'
    * def bytes = pdf()
    * match bytes == '#notnull'
    * assert bytes.length > 100
    * screenshot()
