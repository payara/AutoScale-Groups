<sun:tab id="awsScaleGroupsTab" immediate="$boolean{true}" text="AWS AutoScale Groups">
    <!command
        setSessionAttribute(key="autoScaleGroupsTabs" value="awsScaleGroupsTab");
        gf.redirect(page="#{request.contextPath}/awsScaleGroups/awsScaleGroupsList.jsf?dgName=#{encodedDGName}");
    />
</sun:tab>
