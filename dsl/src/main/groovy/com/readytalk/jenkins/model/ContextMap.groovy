package com.readytalk.jenkins.model

import com.readytalk.util.ClosureGlue
import com.readytalk.util.StringUtils
import groovy.transform.TupleConstructor

interface ContextLookup {
  def lookup(String namespace, String field)
}

interface ContextBind {
  def bind(String namespace, String field, value)
}

/**
 * Hierarchical key-value store
 * Each model element (except components) have an associated context (aka scope)
 *   Setting values is always done in the current scope
 *   Getting values will delegate to parent (outer) scopes if the value doesn't exist in the local scope
 *     Because getting and setting for the same key might refer to different objects,
 *     the getter attempts to return immutable values
 */
class ContextMap implements ContextLookup, ContextBind {
  final ContextLookup parent
  final Map<String,Map<String,Object>> context

  ContextMap() {
    parent = null
    context = [:]
  }

  ContextMap(ContextLookup parent, context) {
    this.parent = parent
    this.context = context
  }

  static ContextLookup mergeLookups(ContextLookup first, ContextLookup second) {
    return ClosureGlue.fallbackIfNull(first.&lookup, second.&lookup) as ContextLookup
  }

  ContextLookup withFallback(ContextLookup fallback) {
    return ContextMap.mergeLookups(this, fallback)
  }

  void bindAppend(ContextLookup withScope, String namespace, String field, value) {
    this.bind(namespace, field, (withScope.lookup(namespace, field) ?: [:]) + value)
  }

  void bindPrepend(ContextLookup withScope, String namespace, String field, value) {
    this.bind(namespace, field, value + (withScope.lookup(namespace, field) ?: [:]))
  }

  //Check this context for value, then check parent context
  def lookup(String componentName, String field) {
    if(componentName == null) return lookup('', field)
    def value
    if(context.get(componentName)?.containsKey(field)) {
      value = context.get(componentName).get(field)
    } else {
      value = parent != null ? parent.lookup(componentName,field) : null
    }

    if(value != null && value instanceof Collection) {
      //This is required to ensure binders can't accidentally modify higher scope defaults for map/list values
      //i.e. 'something.map.put(key,value)' is illegal, but 'something.map = something.map + [key: value]' is okay
      //Not needed for Strings, since those are already immutable by default in the JVM
      return value.asImmutable()
    }
    return value
  }

  //TODO: Verify that bound values actually correspond to real component parameters
  //      Would help catch typos and people doing things they shouldn't that won't show up in the pretty printed output
  def bind(String componentName, String key, value) {
    //TODO: should we support blank scope? i.e. named values with no namespace / component name
    if(componentName == null) return bind('', key, value)
    if(!context.containsKey(componentName)) { context.put(componentName, [:]) }
    context.get(componentName).put(key, value)
    return this
  }

  ContextMap createChildContext() {
    return new ContextMap(this, [:])
  }

}

//Special context that always returns default component values
@TupleConstructor
class DefaultContext implements ContextLookup {
  TypeRegistry registry

  def lookup(String componentName, String field) {
    return registry.lookup(componentName)?.fields?.get(field)
  }
}