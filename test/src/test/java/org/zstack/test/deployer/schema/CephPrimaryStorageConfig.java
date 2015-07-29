//
// This file was generated by the JavaTM Architecture for XML Binding(JAXB) Reference Implementation, v2.2.4-2 
// See <a href="http://java.sun.com/xml/jaxb">http://java.sun.com/xml/jaxb</a> 
// Any modifications to this file will be lost upon recompilation of the source schema. 
// Generated on: 2015.07.29 at 12:16:47 AM PDT 
//


package org.zstack.test.deployer.schema;

import java.util.ArrayList;
import java.util.List;
import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlType;


/**
 * <p>Java class for CephPrimaryStorageConfig complex type.
 * 
 * <p>The following schema fragment specifies the expected content contained within this class.
 * 
 * <pre>
 * &lt;complexType name="CephPrimaryStorageConfig">
 *   &lt;complexContent>
 *     &lt;extension base="{http://zstack.org/schema/zstack}PrimaryStorageConfigBase">
 *       &lt;sequence>
 *         &lt;element name="fsid" type="{http://www.w3.org/2001/XMLSchema}string"/>
 *         &lt;element name="monUrl" type="{http://www.w3.org/2001/XMLSchema}string" maxOccurs="unbounded"/>
 *       &lt;/sequence>
 *     &lt;/extension>
 *   &lt;/complexContent>
 * &lt;/complexType>
 * </pre>
 * 
 * 
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "CephPrimaryStorageConfig", propOrder = {
    "fsid",
    "monUrl"
})
public class CephPrimaryStorageConfig
    extends PrimaryStorageConfigBase
{

    @XmlElement(required = true)
    protected String fsid;
    @XmlElement(required = true)
    protected List<String> monUrl;

    /**
     * Gets the value of the fsid property.
     * 
     * @return
     *     possible object is
     *     {@link String }
     *     
     */
    public String getFsid() {
        return fsid;
    }

    /**
     * Sets the value of the fsid property.
     * 
     * @param value
     *     allowed object is
     *     {@link String }
     *     
     */
    public void setFsid(String value) {
        this.fsid = value;
    }

    /**
     * Gets the value of the monUrl property.
     * 
     * <p>
     * This accessor method returns a reference to the live list,
     * not a snapshot. Therefore any modification you make to the
     * returned list will be present inside the JAXB object.
     * This is why there is not a <CODE>set</CODE> method for the monUrl property.
     * 
     * <p>
     * For example, to add a new item, do as follows:
     * <pre>
     *    getMonUrl().add(newItem);
     * </pre>
     * 
     * 
     * <p>
     * Objects of the following type(s) are allowed in the list
     * {@link String }
     * 
     * 
     */
    public List<String> getMonUrl() {
        if (monUrl == null) {
            monUrl = new ArrayList<String>();
        }
        return this.monUrl;
    }

}