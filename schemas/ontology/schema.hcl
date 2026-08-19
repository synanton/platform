include {
  path = "./_common.hcl"
}

class "Organization" {
  label = "Organization"
  super = ["Thing"]
}

class "Supplier" {
  label = "Supplier"
  super = ["Organization"]
  property "name" {
    path      = "sc:name"
    datatype  = "xsd:string"
    min_count = 1
    max_count = 1
  }
}

class "Customer" {
  label = "Customer"
  super = ["Organization"]
}

class "Product" {
  label = "Product"
  super = ["Thing"]
}

class "Warehouse" {
  label = "Warehouse"
  super = ["Organization"]
}

class "Order" {
  label = "Order"
  super = ["Thing"]
}

class "OrderLine" {
  label = "Order Line"
  super = ["Thing"]
}

class "Shipment" {
  label = "Shipment"
  super = ["Thing"]
}

class "InventoryItem" {
  label = "Inventory Item"
  super = ["Thing"]
}

class "Location" {
  label = "Location"
  super = ["Thing"]
}

class "Address" {
  label = "Address"
  super = ["Location"]
}

class "Country" {
  label = "Country"
  super = ["Location"]
}

class "City" {
  label = "City"
  super = ["Location"]
}

class "PurchaseOrder" {
  label = "Purchase Order"
  super = ["Order"]
}

class "SalesOrder" {
  label = "Sales Order"
  super = ["Order"]
}

class "Carrier" {
  label = "Carrier"
  super = ["Organization"]
}

class "Contract" {
  label = "Contract"
  super = ["Thing"]
}

class "Invoice" {
  label = "Invoice"
  super = ["Thing"]
}

class "Payment" {
  label = "Payment"
  super = ["Thing"]
}

relation "hasSupplier" {
  label  = "has supplier"
  domain = "Product"
  range  = "Supplier"
}

relation "hasCustomer" {
  label  = "has customer"
  domain = "Order"
  range  = "Customer"
}

relation "locatedIn" {
  label  = "located in"
  domain = "Warehouse"
  range  = "Location"
}

relation "containsProduct" {
  label  = "contains product"
  domain = "OrderLine"
  range  = "Product"
}

relation "partOfOrder" {
  label  = "part of order"
  domain = "OrderLine"
  range  = "Order"
}

relation "shipsVia" {
  label  = "ships via"
  domain = "Shipment"
  range  = "Carrier"
}

relation "fulfillsOrder" {
  label  = "fulfills order"
  domain = "Shipment"
  range  = "Order"
}
