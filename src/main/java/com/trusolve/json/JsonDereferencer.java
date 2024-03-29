package com.trusolve.json;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.slf4j.LoggerFactory;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;


public class JsonDereferencer
{
	private static final Logger LOGGER = LoggerFactory.getLogger(JsonDereferencer.class);
	private boolean dereferenceLocalRefs = false;
	
	private JsonNode rootNode;
	private URL rootContext;
	private Map<URL,JsonNode> dependencies = new HashMap<URL,JsonNode>();
	private Map<JsonNode,Map<String,String>> refAliases = new IdentityHashMap<JsonNode,Map<String,String>>();
	private boolean refGlobalInline = false;
	private boolean refGlobalIncludedRefPostfix = false;
	
	public static void main(String[] args)
	{
		try
		{
			URL fileURL = new File(args[0]).toURI().toURL();

			ObjectMapper om = new ObjectMapper();
			om.enable(SerializationFeature.INDENT_OUTPUT);
			ObjectWriter ow = om.writer().withDefaultPrettyPrinter();
			System.out.println(ow.writeValueAsString(new JsonDereferencer(fileURL).dereference()));
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}
	}

	JsonDereferencer(JsonNode rootNode, URL rootContext)
	{
		this.rootNode = rootNode;
		this.rootContext = rootContext;
	}
	
	JsonDereferencer(URL u)
		throws JsonProcessingException, IOException
	{
		this(new ObjectMapper().readTree(u), u);
	}

	JsonDereferencer(File f)
		throws JsonProcessingException, IOException
	{
		this(f.toURI().toURL());
	}

	JsonDereferencer(Reader r, URL rootContext)
		throws JsonProcessingException, IOException
	{
		this(new ObjectMapper().readTree(r), rootContext);
	}
	JsonDereferencer(String s, URL rootContext)
		throws JsonProcessingException, IOException
	{
		this(new ObjectMapper().readTree(s), rootContext);
	}

	
	public static String dereferenceToString( URL u )
		throws JsonProcessingException, IOException, URISyntaxException
	{
		return new JsonDereferencer(u).dereferenceToString();
	}

	public static String dereferenceToString( Reader r )
		throws JsonProcessingException, IOException, URISyntaxException
	{
		return dereferenceToString( r, null );
	}
	
	public static String dereferenceToString( Reader r, URL context )
		throws JsonProcessingException, IOException, URISyntaxException
	{
		return new JsonDereferencer(r,context).dereferenceToString();
	}

	public static JsonNode dereference( URL u )
		throws JsonProcessingException, IOException, URISyntaxException
	{
		return new JsonDereferencer(u).dereference();
	}
	
	
	public String dereferenceToString()
		throws JsonProcessingException, IOException, URISyntaxException
	{
		ObjectMapper om = new ObjectMapper();

		om.enable(SerializationFeature.INDENT_OUTPUT);
		ObjectWriter ow = om.writer().withDefaultPrettyPrinter();

		return(ow.writeValueAsString(dereference()));
	}
		
	public JsonNode dereference()
		throws JsonProcessingException, IOException, URISyntaxException
	{
		return dereference(this.rootNode, this.rootContext);
	}

	private JsonNode dereference( JsonNode o, URL context )
		throws JsonProcessingException, IOException, URISyntaxException
	{
		return dereference(o, context, null);
	}
	
	private void setAliases( JsonNode aliasObject, JsonNode currentDocument )
	{
		if( aliasObject == null )
		{
			LOGGER.trace("No alias object");
			return;
		}
		if( ! aliasObject.isObject() )
		{
			LOGGER.error("$refAliases supplied, but it is not a JSON Object");
			return;
		}
		if( currentDocument == null ) 
		{
			currentDocument = this.rootNode;
		}
		for( Iterator<Map.Entry<String, JsonNode>> i = aliasObject.fields() ; i.hasNext() ; )
		{
			Map.Entry<String, JsonNode> e = i.next();
			String key = e.getKey();
			JsonNode valueNode = e.getValue();
			if( ! valueNode.isValueNode() )
			{
				LOGGER.error("refAlias for key=" + key + " is not a text value");
				continue;
			}
			String value = valueNode.asText();
			LOGGER.debug("Setting refAliases key=" + key + " value=" + value );
			Map<String,String> documentAliases = refAliases.get(currentDocument);
			if( documentAliases == null )
			{
				documentAliases = new HashMap<String,String>();
				refAliases.put(currentDocument,documentAliases);
			}
			documentAliases.put( key, value );
		}
	}
	private JsonNode dereference( JsonNode o, URL context, JsonNode currentDocument )
		throws JsonProcessingException, IOException, URISyntaxException
	{
		if( currentDocument == null ) 
		{
			currentDocument = this.rootNode;
		}
		if( o instanceof ObjectNode )
		{
			ObjectNode jo = (ObjectNode)o;

			setAliases( jo.remove("$refAliases"), currentDocument );
			
			JsonNode refGlobalInline = jo.remove("$refGlobalInline");
			if( refGlobalInline != null && refGlobalInline.isBoolean() ){
			  this.refGlobalInline = refGlobalInline.asBoolean();
			}
			JsonNode refGlobalIncludedRefPostfix = jo.remove("$refGlobalIncludedRefPostfix");
			if( refGlobalIncludedRefPostfix != null && refGlobalIncludedRefPostfix.isBoolean() ){
			  this.refGlobalIncludedRefPostfix = refGlobalIncludedRefPostfix.asBoolean();
			}
			
			// first iterate through each item and dereference it
			for( String key : getFieldNamesSet(jo) )
			{
				JsonNode valueObject = jo.get(key);
				LOGGER.debug( "Processing node for dereference: " + key + ":" + valueObject );
				JsonNode d = dereference(valueObject, context, currentDocument);
				if( valueObject != d )
				{
				  jo.replace(key, d);
				}
			}
			
			// then check if we have a reference here that we need to handle
			JsonNode ref = jo.get("$ref");
			JsonNode refIgnore = jo.remove("$refIgnore");
			JsonNode refInline = jo.remove("$refInline");
			LOGGER.debug("Checking for reference.  ref=" + ref + " / refIgnore=" + refIgnore );
			if( ref != null && refIgnore == null )
			{
				List<String> refs;
					
				// Get the traditional single reference
				if( ref.isTextual() )
				{
					LOGGER.debug("Reference is a single value of " + ref.asText());
					refs = new ArrayList<String>();
					refs.add(ref.asText());
				}
				// Get a customized array reference (requires a merge)
				else if( ref.isArray() )
				{
					refs = getStringList(ref);
					LOGGER.debug("Reference is an array " + refs );
					if( refs == null )
					{
						return(o);
					}
				}
				else
				{
					// If refs is not defined
					LOGGER.warn("Found a ref entry, but the value wasn't valid: " + o);
					return o;
				}
				
				// Begin Processing the references
				for(String refHref : refs)
				{
					LOGGER.debug("Processing reference for " + refHref);
					if( refHref == null ) 
					{
						LOGGER.error("$ref returned as null (" + o.toString() + ").  Ref left intact.");
						return o;
					}
					if( refHref.startsWith("#") )
					{
						LOGGER.debug("Reference is on the document currently being processed");
						if( currentDocument != null && currentDocument != this.rootNode && refHref.length() > 2 && ! this.refGlobalInline )
						{
							LOGGER.debug("Local reference is being imported into the root document");
							jo.put("$ref", addLocalReference( context, currentDocument, refHref.substring(1) ));
							return o;
						}
						if( refs.size() == 1 && ! dereferenceLocalRefs && refInline == null && ! this.refGlobalInline )
						{
							// If there is more than 1 reference we must ALWAYS dereference with a merge operation
							LOGGER.debug("This is a single local reference and dereference locals is off, including as is");
							return o;
						}
					}
					// Remove the reference control variables from the resulting JSON document
					jo.remove("$ref");
					
					if( refHref.startsWith("@") )
					{
						int pointerIndex = refHref.indexOf("#");
						if( pointerIndex > 0 )
						{
							String alias = refHref.substring(1, pointerIndex );
							Map<String,String> documentAliases = refAliases.get(currentDocument);
							String aliasValue = null;
							if( documentAliases != null )
							{
								aliasValue = documentAliases.get(alias);
							}
							if( aliasValue == null )
							{
								LOGGER.error("Reference alias \"" + refHref + "\" specified, but corresponding alias value not found." );
							}
							else
							{
								LOGGER.debug("Replacing refHref alias old value=" + refHref);
								refHref = aliasValue + refHref.substring(pointerIndex);
								LOGGER.debug("Replacing refHref alias new value=" + refHref);
							}
						}
						else
						{
							LOGGER.error("Invalid reference alias: " + refHref);
						}
					}
					// remove the refDeep control variable.
					// Variable set: the system will merge all JSON objects and their descendants
					// Variable unset: the system will merge only the JSON name/value pair within the ref
					JsonNode refDeep = jo.remove("$refDeep");
					LOGGER.debug("refDeep: " + refDeep);
					
					
					// remove the refLocalize control variable.
					// Variable set: the system will take this reference and localize it (doc local ref)
					// Variable unset: the system will merge the reference in place
					JsonNode refLocalize = jo.remove("$refLocalize");
					LOGGER.debug("refLocalize: " + refLocalize);
					
					URL loadLocation = null;
					String fragment = null;
					JsonNode refJson = null;
					if( refHref.startsWith("#") )
					{
						// Reference is on the local document and is intended to be inlined
					  if( currentDocument != this.rootNode ){
					    refJson = currentDocument;
					  }else {
						  refJson = this.rootNode;
					  }
						fragment = refHref.substring(1);
					}
					else
					{
						// Reference is on a remote document
						LOGGER.debug("Context={}, Href={}", context, refHref);
						final URI loadLocationURI = new URI(refHref);
						if( loadLocationURI.getScheme() == null ) {
							final File loadFile = new File("src", loadLocationURI.getPath());
							loadLocation = loadFile.toURI().toURL();
							fragment = loadLocationURI.getFragment();
							
						} else {
							loadLocation = new URL(context, refHref );
							fragment = loadLocation.toURI().getFragment();
						}
						LOGGER.debug("Reference load location is=" + loadLocation);
						refJson = getJsonFromCache(loadLocation);
						if( refJson == null )
						{
							LOGGER.debug("Reference root JSON document loaded from source.");
							refJson = new ObjectMapper().readTree(loadLocation);
							this.dependencies.put(loadLocation, refJson);
						}
						else
						{
							LOGGER.debug("Reference root JSON document loaded from cache.");
						}
					}

					if( fragment != null && fragment.length() > 0 )
					{
						JsonNode refFragment = refJson.at(fragment);

						if( refFragment != null && ! refFragment.isMissingNode() )
						{
							refFragment = dereference(refFragment, loadLocation, refJson);
							if( refLocalize != null )
							{
								LOGGER.debug("Local reference is being created.");
								// 	TODO: review the addLocalRefernce and make sure it will work when passed a fragment
								final String newFragment = addLocalReference( context, refJson, fragment );
								jo.removeAll();
								jo.put("$ref", newFragment);
								return o;
							}
						}

						LOGGER.debug("JSON Fragment pointer=" + fragment);
						refJson = refFragment;
						LOGGER.trace("JSON Fragment=" + refJson);
					}
										
					if( jo.size() == 0 || refJson.isValueNode() || refJson.isArray() )
					{
						LOGGER.debug("Returning the result ref");
						return refJson;
					}
					if( ! ( refJson instanceof ObjectNode ) )
					{
					  LOGGER.error("Unable to properly resolve reference " + refHref );
					  throw new IOException("Unable to properly resolve reference " + refHref);
					}
					if( refDeep != null )
					{
						LOGGER.debug("Merging the ref deep");
						merge(jo, (ObjectNode)refJson, true);
					}
					else
					{
						LOGGER.debug("Merging the ref shallow");
						merge(jo, (ObjectNode)refJson, false);
					}
				}	
			}
		}
		else if( o instanceof ArrayNode )
		{
			ArrayNode ja = (ArrayNode) o;
			for( int i = 0 ; i < ja.size() ; i++ )
			{
				JsonNode t = ja.get(i); 
				JsonNode d = dereference(t, context, currentDocument);
				if( t != d )
				{
					LOGGER.debug("Replacing the ref in the array at position " + i );
					LOGGER.debug("Old value=" + t);
					LOGGER.debug("New value=" + d);
					
					ja.remove(i);
					ja.insert(i, d);
				}
			}
		}
		LOGGER.debug("Returning the final resultant object" );
		return(o);
	}
	
	private Set<String> getFieldNamesSet( final ObjectNode objectNode )
	{
	  final Set<String> returnValue = new HashSet<>();
	  if (objectNode != null) {
      for (Iterator<String> i = objectNode.fieldNames(); i.hasNext();) {
        returnValue.add(i.next());
      } 
    }
    return returnValue;
	}
	
	
	private ObjectNode merge(ObjectNode target, ObjectNode source, boolean mergeDeep )
	{
		List<String> refIncludes = getStringList(target.remove("$refIncludes"));
		List<String> refExcludes = getStringList(target.remove("$refExcludes"));
		ObjectNode arrayProcessingDirectives = null;
		try
		{
			arrayProcessingDirectives = (ObjectNode)target.remove("$refArrayProcessing");
		}
		catch( Exception e )
		{
			LOGGER.error("Could not convert $refArrayProcessing directive to an ObjectNode, is it not the right JSON type (Object)?", e);
		}

		for( Iterator<Map.Entry<String,JsonNode>> i = source.fields() ; i.hasNext() ; )
		{
			Map.Entry<String,JsonNode> e = i.next();
			final String sourceAttributeName = e.getKey();
			final JsonNode sourceNode = e.getValue();
			
			ObjectNode arrayProcessingDirectivesCurrentAttribute = null;
			try{
			  if (arrayProcessingDirectives != null) {
          JsonNode n = arrayProcessingDirectives.get(sourceAttributeName);
          if (n != null && n.isObject()) {
            arrayProcessingDirectivesCurrentAttribute = (ObjectNode) n;
          } 
        }
			}catch(Exception ex){
			  LOGGER.error("Issue getting array attribute directives", ex);
			}
			if( refIncludes != null && ! refIncludes.contains(sourceAttributeName) )
			{
				LOGGER.debug("Ignoring object \"" + sourceAttributeName + "\" since it is NOT in the refIncludes." );
				continue;
			}
			if( refExcludes != null && refExcludes.contains(sourceAttributeName))
			{
				LOGGER.debug("Ignoring object \"" + sourceAttributeName + "\" since it is in the refExcludes." );
				continue;
			}
			
			// force a merge if there are directives for this array by creating an empty array.
			if( sourceNode != null && sourceNode.isArray() && arrayProcessingDirectivesCurrentAttribute != null && ! target.has(sourceAttributeName) ){
			  target.set(sourceAttributeName, JsonNodeFactory.instance.arrayNode());
			}
			// This section processes merge attributes that exist in both objects.
			if( target.has(sourceAttributeName) )
			{
				if( target.get(sourceAttributeName) instanceof ObjectNode && e.getValue() instanceof ObjectNode && mergeDeep )
				{
					LOGGER.debug("Merging key " + sourceAttributeName  );
					merge((ObjectNode)target.get(sourceAttributeName), (ObjectNode)e.getValue(), mergeDeep);
				}
				else if( arrayProcessingDirectives != null && e.getValue().isArray() && target.get(sourceAttributeName).isArray() && arrayProcessingDirectives.get(sourceAttributeName) != null )
				{
					try
					{
						ArrayNode refArrayRemovePartialMatch = (ArrayNode)arrayProcessingDirectives.get(sourceAttributeName).get("$refArrayRemovePartialMatch");
						JsonNode refSetMerge = arrayProcessingDirectives.get(sourceAttributeName).get("$refSetMerge");
						ArrayNode sourceArray = (ArrayNode)e.getValue();
						ArrayNode targetArray = (ArrayNode)target.get(sourceAttributeName);
						
						for( int sourceIndex = 0 ; sourceIndex < sourceArray.size() ; sourceIndex++ )
						{
							if( refArrayRemovePartialMatch != null && isPartialObjectMatch(sourceArray.get(sourceIndex), refArrayRemovePartialMatch ) )
							{
								LOGGER.debug("Removing object in Array merge at index " + sourceIndex);
								continue;
							}
							if( refSetMerge != null )
							{
								if( targetContainsNode( targetArray, sourceArray.get(sourceIndex) ) )
								{
									LOGGER.debug("Removing duplicate object in Array merge at index " + sourceIndex);
									continue;
								}
							}
							targetArray.add(sourceArray.get(sourceIndex));
						}
					}
					catch( Exception e1 )
					{
						LOGGER.error("Issue performing array merge directives.  Leaving target intact.", e1);
					}
				}
				else
				{
					LOGGER.debug("Ignoring key \"" + sourceAttributeName + "\" because it already exists and we are performing a shallow merge.");
				}
			}
			else
			{
				LOGGER.debug("Adding key " + sourceAttributeName  );
				target.set(sourceAttributeName, e.getValue());
			}
		}
		return(target);
	}
	
	
	private List<String> getStringList(JsonNode j)
	{
		if( j == null )
		{
			return null;
		}
		if( ! j.isArray() )
		{
			LOGGER.error("Expected array value and instead got " + j.getClass().getName() );
			return null;
		}
		List<String> r = new ArrayList<String>();
		ArrayNode an = (ArrayNode)j;
		for( Iterator<JsonNode> i = an.elements() ; i.hasNext() ; )
		{
			JsonNode value = i.next();
			if( value.isTextual() )
			{
				r.add(value.asText());
			}
		}
		return r;
	}
	private JsonNode getJsonFromCache(URL url)
	{
		if( url == null)
		{
			return null;
		}
		for( Map.Entry<URL, JsonNode> e : this.dependencies.entrySet() )
		{
			if( url.sameFile(e.getKey()))
			{
				return(e.getValue());
			}
		}
		return null;
	}
	
	private void findAndAddLocalReferences( URL context,  JsonNode sourceDocument, JsonNode refJson )
	{
		if( refJson.isValueNode() )
		{
			return;
		}
		if( refJson.isObject() )
		{
			ObjectNode o = (ObjectNode)refJson;
			for( Iterator<Map.Entry<String, JsonNode>> i = o.fields() ; i.hasNext() ; )
			{
				Map.Entry<String, JsonNode> e = i.next();
				if( "$ref".equals(e.getKey()) && e.getValue().isValueNode() )
				{
					String ref = e.getValue().asText();
					if( ref != null && ref.startsWith("#/") )
					{
						o.put("$ref", addLocalReference( context, sourceDocument, ref.substring(1) ));
					}
				}
				else
				{
					findAndAddLocalReferences( context, sourceDocument, e.getValue() );
				}
			}
		}
		else if( refJson.isArray() )
		{
			ArrayNode o = (ArrayNode)refJson;
			for( JsonNode n : o )
			{
				findAndAddLocalReferences( context, sourceDocument, n );
			}
		}
	}
	
	private String addLocalReference( final URL context,  final JsonNode sourceDocument, final String jsonPath )
	{
	  final String postFix;
	  if( this.refGlobalIncludedRefPostfix ){
	    postFix = getDocPostFix(sourceDocument);
	  } else {
	    postFix = "";
	  }
	  
		String fragment = "#" + jsonPath;
		JsonNode refJson = sourceDocument.at(jsonPath);
		// only attempt to add to the core document if the pointer exists in the target document
		// the root node is an object and the node doesn't already exist in the target document
		if( refJson != null && this.rootNode.isObject() && this.rootNode.at(jsonPath+postFix).isMissingNode() )
		{
			ObjectNode addPoint = (ObjectNode)this.rootNode;
			
			findAndAddLocalReferences( context, sourceDocument, refJson );
			
			StringBuffer jsonPathLocation = new StringBuffer();
			
			for( String nodeName : jsonPath.substring(1).split("/") )
			{
				jsonPathLocation.append("/");
				jsonPathLocation.append(nodeName);
				
				JsonNode current = sourceDocument.at(jsonPathLocation.toString());
				JsonNode target = addPoint.get(nodeName);
				
				if( current == null )
				{
					LOGGER.error("Unable to merge undefined reference " + context + fragment );
					break;
				}
				if( target != null )
				{
					if( target == current )
					{
						break;
					}
					if( target.getNodeType() == current.getNodeType() )
					{
						if( target.isObject() )
						{
							addPoint = (ObjectNode)target;
							continue;
						}
						else
						{
							LOGGER.error("Refusing to merge non object node types " + context + fragment );
							break;
						}
					}
					else
					{
						LOGGER.error("Refusing to merge different reference types at pointer " + context + fragment );
						break;
					}
				}
				if( current == refJson )
				{
					addPoint.set(nodeName + postFix, addExternalLibTag(jsonPath, sourceDocument, refJson));
				}
				else
				{
					if( current.isObject() )
					{
						addPoint = addPoint.putObject(nodeName);
					}
					else
					{
						LOGGER.warn("Reached a non object in JSON Point reference " + context + fragment );
						addPoint.set(nodeName + postFix, current);
						break;
					}
				}
			}
		}
		return fragment + postFix;
	}
	
	private JsonNode addExternalLibTag(final String jsonPath, final JsonNode sourceDocument, final JsonNode refJson) {
    final String documentTag = this.getMavenArtifactId(sourceDocument);
    if( refJson.isObject() && StringUtils.isNotEmpty(documentTag) ) {
      if( jsonPath.matches("/parameters/.*") || jsonPath.matches("/components/.*") || jsonPath.matches("/definitions/.*") ) {
        ObjectNode refJsonObject = (ObjectNode)refJson;
        if( ! refJsonObject.has("x-external-lib")) {
          ObjectNode refJsonObjectCopy = refJsonObject.deepCopy();
          refJsonObjectCopy.set("x-external-lib", TextNode.valueOf(documentTag));
          return refJsonObjectCopy;
        }
      }
    }
    return refJson;
	}
	
	
	private String getDocUrlString( final JsonNode sourceDocument ) {
    for(Entry<URL, JsonNode> e : this.dependencies.entrySet()){
      if( e.getValue() == sourceDocument ){
        return e.getKey().getPath();
      }
    }
    return null;
	}
	
	private String getDocPostFix(final JsonNode sourceDocument){
	  String docUrlString = getDocUrlString(sourceDocument);
	  if( docUrlString != null ) {
	    return "-" + docUrlString.substring(docUrlString.lastIndexOf('/')+1, docUrlString.lastIndexOf('.'));
	  }
	  return "";
	}
	
	private String getMavenArtifactId(final JsonNode sourceDocument ) {
	  String docUrlString = getDocUrlString(sourceDocument);
	  if( docUrlString == null ) {
	    return null;
	  }
	  String[] components = docUrlString.split("/");
	  if( components.length > 3){
	    return components[components.length-3];
	  }
	  return null;
	}
	private boolean isPartialObjectMatch( JsonNode node, ArrayNode nodeList )
	{
		for( int i = 0 ; i < nodeList.size() ; i++ )
		{
			if( isPartialObjectMatch( node, nodeList.get(i) ) )
			{
				return true;
			}
		}
		return false;
	}
	
	private boolean isPartialObjectMatch( JsonNode node, JsonNode partialNode )
	{
		if( node == partialNode )
		{
			return true;
		}
		if( node == null || partialNode == null )
		{
			return false;
		}
		if( node.getNodeType() != partialNode.getNodeType() )
		{
			return false;
		}
		if( node.isObject() && partialNode.isObject() )
		{
			return( isPartialObjectMatch( (ObjectNode)node, (ObjectNode)partialNode ) );
		}
		if( node.isArray() && partialNode.isArray() )
		{
			return( isPartialObjectMatch( (ArrayNode)node, (ArrayNode)partialNode ) );
		}
		if( node.isValueNode() && partialNode.isValueNode() )
		{
			return(node.equals(partialNode));
		}
		return false;
	}
	private boolean isPartialObjectMatch( ObjectNode node, ObjectNode partialNode )
	{
		for( Iterator<Map.Entry<String, JsonNode>> i = partialNode.fields() ; i.hasNext() ; )
		{
			Map.Entry<String,JsonNode> e = i.next();
			if( ! isPartialObjectMatch(node.get(e.getKey()), e.getValue() ) )
			{
				return false;
			}
		}
		return true;
	}
	private boolean isPartialObjectMatch( ArrayNode node, ArrayNode partialNode )
	{
		for( int i = 0; i < partialNode.size() ; i++ )
		{
			if( ! isPartialObjectMatch(node.get(i), partialNode.get(i) ) )
			{
				return false;
			}
		}
		return true;
	}
	private boolean targetContainsNode( ArrayNode targetArray, JsonNode node )
	{
		for( Iterator<JsonNode> i = targetArray.elements() ; i.hasNext() ; )
		{
			JsonNode n = i.next();
			if( n.equals(node) )
			{
				return true;
			}
		}
		return false;
	}
}
